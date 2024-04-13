package zio.kafka.consumer.internal

import org.apache.kafka.common.TopicPartition
import zio.kafka.consumer.Offset
import zio.kafka.consumer.diagnostics.{ DiagnosticEvent, Diagnostics }
import zio.kafka.consumer.internal.PartitionStreamControl.QueueInfo
import zio.kafka.consumer.internal.Runloop.ByteArrayCommittableRecord
import zio.stream.{ Take, ZStream }
import zio.{ Chunk, Clock, Duration, LogAnnotation, Promise, Queue, Ref, UIO, ZIO }

import java.util.concurrent.TimeoutException
import scala.util.control.NoStackTrace

abstract class PartitionStream {
  def tp: TopicPartition
  def queueSize: UIO[Int]
}

/**
 * Provides control and information over a stream that consumes from a partition.
 *
 * @param tp
 *   topic and partition
 * @param stream
 *   the stream
 * @param dataQueue
 *   the queue the stream reads data from
 * @param interruptionPromise
 *   a promise that when completed stops the stream
 * @param completedPromise
 *   the last pulled offset (if any). The promise completes when the stream completed.
 * @param queueInfoRef
 *   used to track the stream's pull deadline, its queue size, and last pulled offset
 * @param maxPollInterval
 *   see [[zio.kafka.consumer.ConsumerSettings.withMaxPollInterval()]]
 */
final class PartitionStreamControl private (
  val tp: TopicPartition,
  stream: ZStream[Any, Throwable, ByteArrayCommittableRecord],
  dataQueue: Queue[Take[Throwable, ByteArrayCommittableRecord]],
  interruptionPromise: Promise[Throwable, Unit],
  val completedPromise: Promise[Nothing, Option[Offset]],
  queueInfoRef: Ref[QueueInfo],
  maxPollInterval: Duration
) extends PartitionStream {
  private val maxPollIntervalNanos = maxPollInterval.toNanos

  private val logAnnotate = ZIO.logAnnotate(
    LogAnnotation("topic", tp.topic()),
    LogAnnotation("partition", tp.partition().toString)
  )

  /** Offer new data for the stream to process. Should be called on every poll, also when `data.isEmpty` */
  private[internal] def offerRecords(data: Chunk[ByteArrayCommittableRecord]): ZIO[Any, Nothing, Unit] =
    ZIO
      .whenZIO(isRunning) {
        if (data.isEmpty) {
          queueInfoRef.update(_.withEmptyPoll)
        } else {
          for {
            now <- Clock.nanoTime
            newPullDeadline = now + maxPollIntervalNanos
            _ <- queueInfoRef.update(_.withOffer(newPullDeadline, data.size))
            _ <- dataQueue.offer(Take.chunk(data))
          } yield ()
        }
      }
      .unit

  def queueSize: UIO[Int] = queueInfoRef.get.map(_.size)

  /**
   * @return
   *   the number of polls there are records idling in the queue. It is increased on every poll (when the queue is
   *   nonEmpty) and reset to 0 when the stream pulls the records
   */
  def outstandingPolls: UIO[Int] = queueInfoRef.get.map(_.outstandingPolls)

  /**
   * @param now
   *   the time as given by `Clock.nanoTime`
   * @return
   *   `true` when the stream has data available, but none has been pulled for more than `maxPollInterval` (since data
   *   became available), `false` otherwise
   */
  private[internal] def maxPollIntervalExceeded(now: NanoTime): UIO[Boolean] =
    queueInfoRef.get.map(_.deadlineExceeded(now))

  /** To be invoked when the partition was lost. */
  private[internal] def lost: UIO[Boolean] = {
    val lostException = new RuntimeException(s"Partition ${tp.toString} was lost") with NoStackTrace
    interruptionPromise.fail(lostException)
  }

  /** To be invoked when the stream is no longer processing. */
  private[internal] def halt: UIO[Boolean] = {
    val timeOutMessage = s"No records were polled for more than $maxPollInterval for topic partition $tp. " +
      "Use ConsumerSettings.withMaxPollInterval to set a longer interval if processing a batch of records " +
      "needs more time."
    val consumeTimeout = new TimeoutException(timeOutMessage) with NoStackTrace
    interruptionPromise.fail(consumeTimeout)
  }

  /** To be invoked when the partition was revoked or otherwise needs to be ended. */
  private[internal] def end: ZIO[Any, Nothing, Unit] =
    logAnnotate {
      ZIO.logDebug(s"Partition ${tp.toString} ending") *>
        dataQueue.offer(Take.end).unit
    }

  /** Returns true when the stream is done. */
  private[internal] def isCompleted: ZIO[Any, Nothing, Boolean] =
    completedPromise.isDone

  /** Returns true when the stream is running. */
  private[internal] def isRunning: ZIO[Any, Nothing, Boolean] =
    isCompleted.negate

  private[internal] val tpStream: (TopicPartition, ZStream[Any, Throwable, ByteArrayCommittableRecord]) =
    (tp, stream)
}

object PartitionStreamControl {

  private[internal] def newPartitionStream(
    tp: TopicPartition,
    commandQueue: Queue[RunloopCommand],
    diagnostics: Diagnostics,
    maxPollInterval: Duration
  ): UIO[PartitionStreamControl] = {
    val maxPollIntervalNanos = maxPollInterval.toNanos

    def registerPull(queueInfo: Ref[QueueInfo], records: Chunk[ByteArrayCommittableRecord]): UIO[Unit] =
      for {
        now <- Clock.nanoTime
        newPullDeadline = now + maxPollIntervalNanos
        _ <- queueInfo.update(_.withPull(newPullDeadline, records))
      } yield ()

    for {
      _                   <- ZIO.logDebug(s"Creating partition stream ${tp.toString}")
      interruptionPromise <- Promise.make[Throwable, Unit]
      completedPromise    <- Promise.make[Nothing, Option[Offset]]
      dataQueue           <- Queue.unbounded[Take[Throwable, ByteArrayCommittableRecord]]
      now                 <- Clock.nanoTime
      queueInfo           <- Ref.make(QueueInfo(now, 0, None, 0))
      requestAndAwaitData =
        for {
          _     <- commandQueue.offer(RunloopCommand.Request(tp))
          _     <- diagnostics.emit(DiagnosticEvent.Request(tp))
          taken <- dataQueue.takeBetween(1, Int.MaxValue)
        } yield taken

      stream = ZStream.logAnnotate(
                 LogAnnotation("topic", tp.topic()),
                 LogAnnotation("partition", tp.partition().toString)
               ) *>
                 ZStream.finalizer {
                   for {
                     qi <- queueInfo.get
                     _  <- completedPromise.succeed(qi.lastPulledOffset)
                     _  <- ZIO.logDebug(s"Partition stream ${tp.toString} has ended")
                   } yield ()
                 } *>
                 ZStream.repeatZIOChunk {
                   // First try to take all records that are available right now.
                   // When no data is available, request more data and await its arrival.
                   dataQueue.takeAll.flatMap(data => if (data.isEmpty) requestAndAwaitData else ZIO.succeed(data))
                 }.flattenTake.chunksWith { s =>
                   s.tap(records => registerPull(queueInfo, records))
                     // Due to https://github.com/zio/zio/issues/8515 we cannot use Zstream.interruptWhen.
                     .mapZIO(chunk => interruptionPromise.await.whenZIO(interruptionPromise.isDone).as(chunk))
                 }
    } yield new PartitionStreamControl(
      tp,
      stream,
      dataQueue,
      interruptionPromise,
      completedPromise,
      queueInfo,
      maxPollInterval
    )
  }

  // The `pullDeadline` is only relevant when `size > 0`. We initialize `pullDeadline` as soon as size goes above 0.
  // (Note that theoretically `size` can go below 0 when the update operations are reordered.)
  private final case class QueueInfo(
    pullDeadline: NanoTime,
    size: Int,
    lastPulledOffset: Option[Offset],
    outstandingPolls: Int
  ) {
    // To be called when a poll resulted in 0 records.
    def withEmptyPoll: QueueInfo =
      copy(outstandingPolls = outstandingPolls + 1)

    // To be called when a poll resulted in >0 records.
    def withOffer(newPullDeadline: NanoTime, recordCount: Int): QueueInfo =
      QueueInfo(
        pullDeadline = if (size <= 0) newPullDeadline else pullDeadline,
        size = size + recordCount,
        lastPulledOffset = lastPulledOffset,
        outstandingPolls = outstandingPolls + 1
      )

    def withPull(newPullDeadline: NanoTime, records: Chunk[ByteArrayCommittableRecord]): QueueInfo =
      QueueInfo(
        pullDeadline = newPullDeadline,
        size = size - records.size,
        lastPulledOffset = records.lastOption.map(_.offset).orElse(lastPulledOffset),
        outstandingPolls = 0
      )

    def deadlineExceeded(now: NanoTime): Boolean =
      size > 0 && pullDeadline <= now
  }

}
