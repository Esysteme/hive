/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.spark;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.MapOperator;
import org.apache.hadoop.hive.ql.exec.MapredContext;
import org.apache.hadoop.hive.ql.exec.ObjectCache;
import org.apache.hadoop.hive.ql.exec.ObjectCacheFactory;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.OperatorUtils;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.mr.ExecMapper.ReportStats;
import org.apache.hadoop.hive.ql.exec.mr.ExecMapperContext;
import org.apache.hadoop.hive.ql.exec.vector.VectorMapOperator;
import org.apache.hadoop.hive.ql.log.PerfLogger;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.MapWork;
import org.apache.hadoop.hive.ql.plan.MapredLocalWork;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;


/**
 * Clone from ExecMapper. SparkMapRecordHandler is the bridge between the spark framework and
 * the Hive operator pipeline at execution time. It's main responsibilities are:
 *
 * - Load and setup the operator pipeline from XML
 * - Run the pipeline by transforming key value pairs to records and forwarding them to the operators
 * - Stop execution when the "limit" is reached
 * - Catch and handle errors during execution of the operators.
 *
 */
public class SparkMapRecordHandler extends SparkRecordHandler {
  private static final Log LOG = LogFactory.getLog(SparkMapRecordHandler.class);
  private static final String PLAN_KEY = "__MAP_PLAN__";
  private MapOperator mo;
  private MapredLocalWork localWork = null;
  private boolean isLogInfoEnabled = false;
  private ExecMapperContext execContext;

  public <K, V> void init(JobConf job, OutputCollector<K, V> output, Reporter reporter) throws Exception {
    perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.SPARK_INIT_OPERATORS);
    super.init(job, output, reporter);

    isLogInfoEnabled = LOG.isInfoEnabled();
    ObjectCache cache = ObjectCacheFactory.getCache(job);

    try {
      jc = job;
      execContext = new ExecMapperContext(jc);
      // create map and fetch operators
      MapWork mrwork = (MapWork) cache.retrieve(PLAN_KEY);
      if (mrwork == null) {
        mrwork = Utilities.getMapWork(job);
        cache.cache(PLAN_KEY, mrwork);
      } else {
        Utilities.setMapWork(job, mrwork);
      }
      if (mrwork.getVectorMode()) {
        mo = new VectorMapOperator();
      } else {
        mo = new MapOperator();
      }
      mo.setConf(mrwork);

      // initialize map operator
      mo.setChildren(job);
      LOG.info(mo.dump(0));
      // initialize map local work
      localWork = mrwork.getMapRedLocalWork();
      execContext.setLocalWork(localWork);

      MapredContext.init(true, new JobConf(jc));
      MapredContext.get().setReporter(reporter);

      mo.setExecContext(execContext);
      mo.initializeLocalWork(jc);
      mo.initialize(jc, null);

      OperatorUtils.setChildrenCollector(mo.getChildOperators(), output);
      mo.setReporter(rp);

      if (localWork == null) {
        return;
      }

      //The following code is for mapjoin
      //initialize all the dummy ops
      LOG.info("Initializing dummy operator");
      List<Operator<? extends OperatorDesc>> dummyOps = localWork.getDummyParentOp();
      for (Operator<? extends OperatorDesc> dummyOp : dummyOps) {
        dummyOp.setExecContext(execContext);
        dummyOp.initialize(jc, null);
      }
    } catch (Throwable e) {
      abort = true;
      if (e instanceof OutOfMemoryError) {
        // will this be true here?
        // Don't create a new object if we are already out of memory
        throw (OutOfMemoryError) e;
      } else {
        throw new RuntimeException("Map operator initialization failed: " + e, e);
      }
    }
    perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.SPARK_INIT_OPERATORS);
  }

  @Override
  public void processRow(Object key, Object value) throws IOException {
    // reset the execContext for each new row
    execContext.resetRow();

    try {
      // Since there is no concept of a group, we don't invoke
      // startGroup/endGroup for a mapper
      mo.process((Writable) value);
      if (isLogInfoEnabled) {
        logMemoryInfo();
      }
    } catch (Throwable e) {
      abort = true;
      Utilities.setMapWork(jc, null);
      if (e instanceof OutOfMemoryError) {
        // Don't create a new object if we are already out of memory
        throw (OutOfMemoryError) e;
      } else {
        String msg = "Error processing row: " + e;
        LOG.fatal(msg, e);
        throw new RuntimeException(msg, e);
      }
    }
  }

  @Override
  public <E> void processRow(Object key, Iterator<E> values) throws IOException {
    throw new UnsupportedOperationException("Do not support this method in SparkMapRecordHandler.");
  }

  @Override
  public void close() {
    // No row was processed
    if (oc == null) {
      LOG.trace("Close called. no row processed by map.");
    }

    // check if there are IOExceptions
    if (!abort) {
      abort = execContext.getIoCxt().getIOExceptions();
    }

    // detecting failed executions by exceptions thrown by the operator tree
    // ideally hadoop should let us know whether map execution failed or not
    try {
      mo.close(abort);

      //for close the local work
      if (localWork != null) {
        List<Operator<? extends OperatorDesc>> dummyOps = localWork.getDummyParentOp();

        for (Operator<? extends OperatorDesc> dummyOp : dummyOps) {
          dummyOp.close(abort);
        }
      }

      if (isLogInfoEnabled) {
        logCloseInfo();
      }

      ReportStats rps = new ReportStats(rp, jc);
      mo.preorderMap(rps);
      return;
    } catch (Exception e) {
      if (!abort) {
        // signal new failure to map-reduce
        String msg = "Hit error while closing operators - failing tree: " + e;
        LOG.error(msg, e);
        throw new IllegalStateException(msg, e);
      }
    } finally {
      MapredContext.close();
      Utilities.clearWorkMap();
    }
  }

  @Override
  public  boolean getDone() {
    return mo.getDone();
  }
}
