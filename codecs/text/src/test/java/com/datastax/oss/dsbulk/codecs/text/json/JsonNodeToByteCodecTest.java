/*
 * Copyright DataStax, Inc.
 *
 * This software is subject to the below license agreement.
 * DataStax may make changes to the agreement from time to time,
 * and will post the amended terms at
 * https://www.datastax.com/terms/datastax-dse-bulk-utility-license-terms.
 */
package com.datastax.oss.dsbulk.codecs.text.json;

import static com.datastax.oss.dsbulk.codecs.text.TextConversionContext.JSON_NODE_TYPE;
import static com.datastax.oss.dsbulk.codecs.text.json.JsonCodecUtils.JSON_NODE_FACTORY;
import static com.datastax.oss.dsbulk.tests.assertions.TestAssertions.assertThat;

import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.dsbulk.codecs.ConversionContext;
import com.datastax.oss.dsbulk.codecs.ConvertingCodecFactory;
import com.datastax.oss.dsbulk.codecs.text.TextConversionContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsonNodeToByteCodecTest {

  private JsonNodeToByteCodec codec;

  @BeforeEach
  void setUp() {
    ConversionContext context = new TextConversionContext().setNullStrings("NULL");
    ConvertingCodecFactory codecFactory = new ConvertingCodecFactory(context);
    codec =
        (JsonNodeToByteCodec)
            codecFactory.<JsonNode, Byte>createConvertingCodec(
                DataTypes.TINYINT, JSON_NODE_TYPE, true);
  }

  @Test
  void should_convert_from_valid_external() {
    assertThat(codec)
        .convertsFromExternal(JSON_NODE_FACTORY.numberNode((byte) 0))
        .toInternal((byte) 0)
        .convertsFromExternal(JSON_NODE_FACTORY.numberNode((byte) 127))
        .toInternal((byte) 127)
        .convertsFromExternal(JSON_NODE_FACTORY.numberNode((byte) -128))
        .toInternal((byte) -128)
        .convertsFromExternal(JSON_NODE_FACTORY.textNode("0"))
        .toInternal((byte) 0)
        .convertsFromExternal(JSON_NODE_FACTORY.textNode("127"))
        .toInternal((byte) 127)
        .convertsFromExternal(JSON_NODE_FACTORY.textNode("-128"))
        .toInternal((byte) -128)
        .convertsFromExternal(JSON_NODE_FACTORY.textNode("1970-01-01T00:00:00Z"))
        .toInternal((byte) 0)
        .convertsFromExternal(JSON_NODE_FACTORY.textNode("TRUE"))
        .toInternal((byte) 1)
        .convertsFromExternal(JSON_NODE_FACTORY.textNode("FALSE"))
        .toInternal((byte) 0)
        .convertsFromExternal(JSON_NODE_FACTORY.textNode(""))
        .toInternal(null)
        .convertsFromExternal(null)
        .toInternal(null)
        .convertsFromExternal(JSON_NODE_FACTORY.textNode("NULL"))
        .toInternal(null);
  }

  @Test
  void should_convert_from_valid_internal() {
    assertThat(codec)
        .convertsFromInternal((byte) 0)
        .toExternal(JSON_NODE_FACTORY.numberNode((byte) 0))
        .convertsFromInternal((byte) 127)
        .toExternal(JSON_NODE_FACTORY.numberNode((byte) 127))
        .convertsFromInternal((byte) -128)
        .toExternal(JSON_NODE_FACTORY.numberNode((byte) -128))
        .convertsFromInternal(null)
        .toExternal(null);
  }

  @Test
  void should_not_convert_from_invalid_external() {
    assertThat(codec)
        .cannotConvertFromExternal(JSON_NODE_FACTORY.textNode("not a valid byte"))
        .cannotConvertFromExternal(JSON_NODE_FACTORY.numberNode(1.2))
        .cannotConvertFromExternal(JSON_NODE_FACTORY.numberNode(128))
        .cannotConvertFromExternal(JSON_NODE_FACTORY.numberNode(-129))
        .cannotConvertFromExternal(JSON_NODE_FACTORY.textNode("2000-01-01T00:00:00Z")) // overflow
    ;
  }
}