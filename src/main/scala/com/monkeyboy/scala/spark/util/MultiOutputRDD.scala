package com.monkeyboy.scala.spark.util

import java.text.SimpleDateFormat
import java.util.Date
import org.apache.hadoop.io.{DataInputBuffer, NullWritable, Text}
import org.apache.hadoop.mapred.RawKeyValueIterator
import org.apache.hadoop.mapreduce.counters.GenericCounter
import org.apache.hadoop.mapreduce.lib.output.{TextOutputFormat, LazyOutputFormat}
import org.apache.hadoop.mapreduce.task.{ReduceContextImpl, TaskAttemptContextImpl}
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl.DummyReporter
import org.apache.hadoop.util.Progress
import org.apache.spark._
import org.apache.hadoop.conf.{Configurable, Configuration}
import org.apache.spark.rdd.RDD
import org.apache.hadoop.mapreduce._
import scala.reflect.ClassTag
import com.monkeyboy.java.hadoop.util.MultipleOutputs

class MultiOutputRDD[K, V](self: RDD[(String, (K, V))])
                          (implicit kt: ClassTag[K], vt: ClassTag[V], ord: Ordering[K] = null)
  extends Serializable  {

  def saveAsMultiTextFiles(path: String) {
    new MultiOutputRDD(self.map(x => (x._1, (NullWritable.get, new Text(x._2._2.toString)))))
      .saveAsNewHadoopMultiOutputs[TextOutputFormat[NullWritable, Text]](path)
  }

  def saveAsNewHadoopMultiOutputs[F <: OutputFormat[K, V]](path: String, conf: Configuration = self.context.hadoopConfiguration)(implicit fm: ClassTag[F]) {
    // Rename this as hadoopConf internally to avoid shadowing (see SPARK-2038).
    val hadoopConf = conf
    val job = new Job(hadoopConf)
    job.setOutputKeyClass(kt.runtimeClass)
    job.setOutputValueClass(vt.runtimeClass)
    LazyOutputFormat.setOutputFormatClass(job, fm.runtimeClass.asInstanceOf[Class[F]])
    job.getConfiguration.set("mapred.output.dir", path)
    saveAsNewAPIHadoopDatasetMultiOutputs(job.getConfiguration)
  }

  def saveAsNewAPIHadoopDatasetMultiOutputs(conf: Configuration) {
    // Rename this as hadoopConf internally to avoid shadowing (see SPARK-2038).
    val hadoopConf = conf
    val job = new Job(hadoopConf)
    val formatter = new SimpleDateFormat("yyyyMMddHHmm")
    val jobtrackerID = formatter.format(new Date())
    val stageId = self.id
    val wrappedConf = new SerializableWritable(job.getConfiguration)
    val outfmt = job.getOutputFormatClass
    val jobFormat = outfmt.newInstance

    if (conf.getBoolean("spark.hadoop.validateOutputSpecs", true)) {
      // FileOutputFormat ignores the filesystem parameter
      jobFormat.checkOutputSpecs(job)
    }

    val writeShard = (context: TaskContext, itr: Iterator[(String, (K, V))]) => {
      // Hadoop wants a 32-bit task attempt ID, so if ours is bigger than Int.MaxValue, roll it
      // around by taking a mod. We expect that no task will be attempted 2 billion times.
//      val attemptNumber = (context.attemptId % Int.MaxValue).toInt
      val attemptNumber = (context.attemptNumber % Int.MaxValue).toInt
      /* "reduce task" <split #> <attempt # = spark task #> */
      val attemptId = new TaskAttemptID(jobtrackerID, stageId, TaskType.REDUCE, context.partitionId, attemptNumber)
      val hadoopContext = new TaskAttemptContextImpl(wrappedConf.value, attemptId)
      val format = outfmt.newInstance
      format match {
        case c: Configurable => c.setConf(wrappedConf.value)
        case _ => ()
      }
      val committer = format.getOutputCommitter(hadoopContext)

      committer.setupTask(hadoopContext)
      val recordWriter = format.getRecordWriter(hadoopContext).asInstanceOf[RecordWriter[K, V]]

      val taskInputOutputContext = new ReduceContextImpl(wrappedConf.value, attemptId, new DummyIterator(itr), new GenericCounter, new GenericCounter,
        recordWriter, committer, new DummyReporter, null, kt.runtimeClass, vt.runtimeClass)
      val writer = new MultipleOutputs(taskInputOutputContext)

      try {
        while (itr.hasNext) {
          val pair = itr.next()
          writer.write(pair._2._1, pair._2._2, pair._1)
        }
      } finally {
        writer.close()
      }
      committer.commitTask(hadoopContext)
      1
    }: Int

    val jobAttemptId = new TaskAttemptID(jobtrackerID, stageId, TaskType.MAP, 0, 0)
    val jobTaskContext = new TaskAttemptContextImpl(wrappedConf.value, jobAttemptId)
    val jobCommitter = jobFormat.getOutputCommitter(jobTaskContext)
    jobCommitter.setupJob(jobTaskContext)
    self.context.runJob(self, writeShard)
    jobCommitter.commitJob(jobTaskContext)
  }

  class DummyIterator(itr: Iterator[_]) extends RawKeyValueIterator {
    def getKey: DataInputBuffer = null
    def getValue: DataInputBuffer = null
    def getProgress: Progress = null
    def next = itr.hasNext
    def close() { }
  }
}

object MultiOutputRDD {
  implicit def rddToMultiOutputRDD[V](rdd: RDD[(String, V)])(implicit vt: ClassTag[V]) = {
    new MultiOutputRDD(rdd.map(x => (x._1, (null, x._2))))
  }
}