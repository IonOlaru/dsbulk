/*
 * Copyright DataStax, Inc.
 *
 * This software is subject to the below license agreement.
 * DataStax may make changes to the agreement from time to time,
 * and will post the amended terms at
 * https://www.datastax.com/terms/datastax-dse-bulk-utility-license-terms.
 */
package com.datastax.oss.dsbulk.workflow.commons.statement;

import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.shaded.guava.common.base.MoreObjects;
import com.datastax.oss.dsbulk.connectors.api.Record;

public class UnmappableStatement extends BulkSimpleStatement<Record> {

  private final Throwable error;

  public UnmappableStatement(Record record, Throwable error) {
    super(record, SimpleStatement.newInstance(error.getMessage()));
    this.error = error;
  }

  public Throwable getError() {
    return error;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("record", getSource())
        .add("error", error)
        .toString();
  }
}