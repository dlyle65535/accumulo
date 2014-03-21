/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.trace.DistributedTrace;
import org.apache.accumulo.core.util.AddressUtil;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.core.util.Version;
import org.apache.accumulo.core.volume.Volume;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.server.client.HdfsZooInstance;
import org.apache.accumulo.server.conf.ServerConfiguration;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.server.util.time.SimpleTimer;
import org.apache.accumulo.server.watcher.MonitorLog4jWatcher;
import org.apache.accumulo.server.zookeeper.ZooReaderWriter;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.xml.DOMConfigurator;
import org.apache.zookeeper.KeeperException;

public class Accumulo {
  
  private static final Logger log = Logger.getLogger(Accumulo.class);
  
  public static synchronized void updateAccumuloVersion(VolumeManager fs) {
    for (Volume volume : fs.getVolumes()) {
      try {
        if (getAccumuloPersistentVersion(fs) == ServerConstants.PREV_DATA_VERSION) {
          log.debug("Attempting to upgrade " + volume);
          Path dataVersionLocation = ServerConstants.getDataVersionLocation(volume);
          fs.create(new Path(dataVersionLocation, Integer.toString(ServerConstants.DATA_VERSION))).close();

          Path prevDataVersionLoc = new Path(dataVersionLocation, Integer.toString(ServerConstants.PREV_DATA_VERSION));
          if (!fs.delete(prevDataVersionLoc)) {
            throw new RuntimeException("Could not delete previous data version location (" + prevDataVersionLoc + ") for " + volume);
          }
        }
      } catch (IOException e) {
        throw new RuntimeException("Unable to set accumulo version: an error occurred.", e);
      }
    }
  }
  
  public static synchronized int getAccumuloPersistentVersion(FileSystem fs, Path path) {
    int dataVersion;
    try {
      FileStatus[] files = fs.listStatus(path);
      if (files == null || files.length == 0) {
        dataVersion = -1; // assume it is 0.5 or earlier
      } else {
        dataVersion = Integer.parseInt(files[0].getPath().getName());
      }
      return dataVersion;
    } catch (IOException e) {
      throw new RuntimeException("Unable to read accumulo version: an error occurred.", e);
    }
  }
  
  public static synchronized int getAccumuloPersistentVersion(VolumeManager fs) {
    // It doesn't matter which Volume is used as they should all have the data version stored
    Volume v = fs.getVolumes().iterator().next();
    Path path = ServerConstants.getDataVersionLocation(v);
    return getAccumuloPersistentVersion(v.getFileSystem(), path);
  }

  public static synchronized Path getAccumuloInstanceIdPath(VolumeManager fs) {
    // It doesn't matter which Volume is used as they should all have the instance ID stored
    Volume v = fs.getVolumes().iterator().next();
    return ServerConstants.getInstanceIdLocation(v);
  }

  public static void enableTracing(String address, String application) {
    try {
      DistributedTrace.enable(HdfsZooInstance.getInstance(), ZooReaderWriter.getInstance(), application, address);
    } catch (Exception ex) {
      log.error("creating remote sink for trace spans", ex);
    }
  }
  
  public static void init(VolumeManager fs, ServerConfiguration config, String application) throws UnknownHostException {
    
    System.setProperty("org.apache.accumulo.core.application", application);
    
    if (System.getenv("ACCUMULO_LOG_DIR") != null)
      System.setProperty("org.apache.accumulo.core.dir.log", System.getenv("ACCUMULO_LOG_DIR"));
    else
      System.setProperty("org.apache.accumulo.core.dir.log", System.getenv("ACCUMULO_HOME") + "/logs/");
    
    String localhost = InetAddress.getLocalHost().getHostName();
    System.setProperty("org.apache.accumulo.core.ip.localhost.hostname", localhost);
    
    int logPort = config.getConfiguration().getPort(Property.MONITOR_LOG4J_PORT);
    System.setProperty("org.apache.accumulo.core.host.log.port", Integer.toString(logPort));
    
    // Use a specific log config, if it exists
    String logConfig = String.format("%s/%s_logger.xml", System.getenv("ACCUMULO_CONF_DIR"), application);
    if (!new File(logConfig).exists()) {
      // otherwise, use the generic config
      logConfig = String.format("%s/generic_logger.xml", System.getenv("ACCUMULO_CONF_DIR"));
    }
    // Turn off messages about not being able to reach the remote logger... we protect against that.
    LogLog.setQuietMode(true);

    // Read the auditing config
    String auditConfig = String.format("%s/auditLog.xml", System.getenv("ACCUMULO_CONF_DIR"));

    DOMConfigurator.configureAndWatch(auditConfig, 5000);

    // Configure logging using information advertised in zookeeper by the monitor
    new MonitorLog4jWatcher(config.getInstance().getInstanceID(), logConfig, 5000).start();

    log.info(application + " starting");
    log.info("Instance " + config.getInstance().getInstanceID());
    int dataVersion = Accumulo.getAccumuloPersistentVersion(fs);
    log.info("Data Version " + dataVersion);
    Accumulo.waitForZookeeperAndHdfs(fs);
    
    Version codeVersion = new Version(Constants.VERSION);
    if (dataVersion != ServerConstants.DATA_VERSION && dataVersion != ServerConstants.PREV_DATA_VERSION) {
      throw new RuntimeException("This version of accumulo (" + codeVersion + ") is not compatible with files stored using data version " + dataVersion);
    }
    
    TreeMap<String,String> sortedProps = new TreeMap<String,String>();
    for (Entry<String,String> entry : config.getConfiguration())
      sortedProps.put(entry.getKey(), entry.getValue());
    
    for (Entry<String,String> entry : sortedProps.entrySet()) {
      String key = entry.getKey();
      log.info(key + " = " + (Property.isSensitive(key) ? "<hidden>" : entry.getValue()));
    }
    
    monitorSwappiness(config.getConfiguration());
  }
  
  /**
   * 
   */
  public static void monitorSwappiness(AccumuloConfiguration config) {
    SimpleTimer.getInstance(config).schedule(new Runnable() {
      @Override
      public void run() {
        try {
          String procFile = "/proc/sys/vm/swappiness";
          File swappiness = new File(procFile);
          if (swappiness.exists() && swappiness.canRead()) {
            InputStream is = new FileInputStream(procFile);
            try {
              byte[] buffer = new byte[10];
              int bytes = is.read(buffer);
              String setting = new String(buffer, 0, bytes, Constants.UTF8);
              setting = setting.trim();
              if (bytes > 0 && Integer.parseInt(setting) > 10) {
                log.warn("System swappiness setting is greater than ten (" + setting + ") which can cause time-sensitive operations to be delayed. "
                    + " Accumulo is time sensitive because it needs to maintain distributed lock agreement.");
              }
            } finally {
              is.close();
            }
          }
        } catch (Throwable t) {
          log.error(t, t);
        }
      }
    }, 1000, 10 * 60 * 1000);
  }
  
  public static void waitForZookeeperAndHdfs(VolumeManager fs) {
    log.info("Attempting to talk to zookeeper");
    while (true) {
      try {
        ZooReaderWriter.getInstance().getChildren(Constants.ZROOT);
        break;
      } catch (InterruptedException e) {
        // ignored
      } catch (KeeperException ex) {
        log.info("Waiting for accumulo to be initialized");
        UtilWaitThread.sleep(1000);
      }
    }
    log.info("Zookeeper connected and initialized, attemping to talk to HDFS");
    long sleep = 1000;
    int unknownHostTries = 3;
    while (true) {
      try {
        if (fs.isReady())
          break;
        log.warn("Waiting for the NameNode to leave safemode");
      } catch (IOException ex) {
        log.warn("Unable to connect to HDFS", ex);
      } catch (IllegalArgumentException exception) {
        /* Unwrap the UnknownHostException so we can deal with it directly */
        if (exception.getCause() instanceof UnknownHostException) {
          if (unknownHostTries > 0) {
            log.warn("Unable to connect to HDFS, will retry. cause: " + exception.getCause());
            /* We need to make sure our sleep period is long enough to avoid getting a cached failure of the host lookup. */
            sleep = Math.max(sleep, (AddressUtil.getAddressCacheNegativeTtl((UnknownHostException)(exception.getCause()))+1)*1000);
          } else {
            log.error("Unable to connect to HDFS and have exceeded max number of retries.", exception);
            throw exception;
          }
          unknownHostTries--;
        } else {
          throw exception;
        }
      }
      log.info("Backing off due to failure; current sleep period is " + sleep / 1000. + " seconds");
      UtilWaitThread.sleep(sleep);
      /* Back off to give transient failures more time to clear. */
      sleep = Math.min(60 * 1000, sleep * 2);
    }
    log.info("Connected to HDFS");
  }
  
}
