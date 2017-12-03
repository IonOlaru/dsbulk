/*
 * Copyright DataStax Inc.
 *
 * This software can be used solely with DataStax Enterprise. Please consult the license at
 * http://www.datastax.com/terms/datastax-dse-driver-license-terms
 */
package com.datastax.dsbulk.engine.internal.settings;

import com.datastax.dsbulk.commons.config.LoaderConfig;
import com.datastax.dsbulk.commons.internal.config.ConfigUtils;
import com.typesafe.config.ConfigException;

/** */
public class EngineSettings {

  private static final String DRY_RUN = "dryRun";
  private static final String EXECUTION_ID = "executionId";

  private final LoaderConfig config;

  private boolean dryRun;
  private String executionId;

  EngineSettings(LoaderConfig config) {
    this.config = config;
  }

  public void init() {
    try {
      dryRun = config.getBoolean(DRY_RUN);
      executionId = config.getString(EXECUTION_ID);
    } catch (ConfigException e) {
      throw ConfigUtils.configExceptionToBulkConfigurationException(e, "engine");
    }
  }

  public boolean isDryRun() {
    return dryRun;
  }

  String getCustomExecutionIdTemplate() {
    return executionId;
  }
}
