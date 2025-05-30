// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.google.common.base.Joiner;
import com.yahoo.collections.Tuple2;
import com.yahoo.document.ArrayDataType;
import com.yahoo.document.DataType;
import com.yahoo.document.Document;
import com.yahoo.document.DocumentId;
import com.yahoo.document.DocumentOperation;
import com.yahoo.document.DocumentPut;
import com.yahoo.document.DocumentRemove;
import com.yahoo.document.DocumentType;
import com.yahoo.document.DocumentTypeManager;
import com.yahoo.document.DocumentUpdate;
import com.yahoo.document.Field;
import com.yahoo.document.MapDataType;
import com.yahoo.document.PositionDataType;
import com.yahoo.document.StructDataType;
import com.yahoo.document.TensorDataType;
import com.yahoo.document.WeightedSetDataType;
import com.yahoo.document.datatypes.Array;
import com.yahoo.document.datatypes.BoolFieldValue;
import com.yahoo.document.datatypes.FieldValue;
import com.yahoo.document.datatypes.IntegerFieldValue;
import com.yahoo.document.datatypes.MapFieldValue;
import com.yahoo.document.datatypes.Raw;
import com.yahoo.document.datatypes.StringFieldValue;
import com.yahoo.document.datatypes.Struct;
import com.yahoo.document.datatypes.TensorFieldValue;
import com.yahoo.document.datatypes.WeightedSet;
import com.yahoo.document.internal.GeoPosType;
import com.yahoo.document.json.readers.DocumentParseInfo;
import com.yahoo.document.json.readers.VespaJsonDocumentReader;
import com.yahoo.document.serialization.DocumentSerializer;
import com.yahoo.document.serialization.DocumentSerializerFactory;
import com.yahoo.document.update.AddValueUpdate;
import com.yahoo.document.update.ArithmeticValueUpdate;
import com.yahoo.document.update.ArithmeticValueUpdate.Operator;
import com.yahoo.document.update.AssignValueUpdate;
import com.yahoo.document.update.ClearValueUpdate;
import com.yahoo.document.update.FieldUpdate;
import com.yahoo.document.update.MapValueUpdate;
import com.yahoo.document.update.TensorAddUpdate;
import com.yahoo.document.update.TensorModifyUpdate;
import com.yahoo.document.update.TensorRemoveUpdate;
import com.yahoo.document.update.ValueUpdate;
import com.yahoo.io.GrowableByteBuffer;
import com.yahoo.tensor.IndexedTensor;
import com.yahoo.tensor.MappedTensor;
import com.yahoo.tensor.MixedTensor;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorType;
import com.yahoo.text.Utf8;
import com.yahoo.yolean.Exceptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.yahoo.document.json.readers.SingleValueReader.UPDATE_DECREMENT;
import static com.yahoo.document.json.readers.SingleValueReader.UPDATE_DIVIDE;
import static com.yahoo.document.json.readers.SingleValueReader.UPDATE_INCREMENT;
import static com.yahoo.document.json.readers.SingleValueReader.UPDATE_MULTIPLY;
import static com.yahoo.test.json.JsonTestHelper.inputJson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Basic test of JSON streams to Vespa document instances.
 *
 * @author Steinar Knutsen
 * @author bratseth
 */
public class JsonReaderTestCase {

    private DocumentTypeManager types;
    private JsonFactory parserFactory;

    @Before
    public void setUp() throws Exception {
        parserFactory = new JsonFactory();
        types = new DocumentTypeManager();
        {
            DocumentType x = new DocumentType("smoke");
            x.addField(new Field("something", DataType.STRING));
            x.addField(new Field("nalle", DataType.STRING));
            x.addField(new Field("field1", DataType.STRING));
            x.addField(new Field("field2", DataType.STRING));
            x.addField(new Field("int1", DataType.INT));
            x.addField(new Field("flag", DataType.BOOL));
            x.addField(new Field("tensor1", DataType.getTensor(TensorType.fromSpec("tensor(x{})"))));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("mirrors");
            StructDataType woo = new StructDataType("woo");
            woo.addField(new Field("sandra", DataType.STRING));
            woo.addField(new Field("cloud", DataType.STRING));
            x.addField(new Field("skuggsjaa", woo));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testarray");
            DataType d = new ArrayDataType(DataType.STRING);
            x.addField(new Field("actualarray", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testset");
            DataType d = new WeightedSetDataType(DataType.STRING, true, true);
            x.addField(new Field("actualset", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testmap");
            DataType d = new MapDataType(DataType.STRING, DataType.STRING);
            x.addField(new Field("actualmap", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testraw");
            DataType d = DataType.RAW;
            x.addField(new Field("actualraw", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testMapStringToArrayOfInt");
            DataType value = new ArrayDataType(DataType.INT);
            DataType d = new MapDataType(DataType.STRING, value);
            x.addField(new Field("actualMapStringToArrayOfInt", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testArrayOfArrayOfInt");
            DataType inner = new ArrayDataType(DataType.INT);
            DataType outer = new ArrayDataType(inner);
            x.addField(new Field("arrayOfArrayOfInt", outer));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testsinglepos");
            DataType d = PositionDataType.INSTANCE;
            x.addField(new Field("singlepos", d));
            x.addField(new Field("geopos", new GeoPosType(8)));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testtensor");
            x.addField(new Field("sparse_single_dimension_tensor",
                                 new TensorDataType(new TensorType.Builder().mapped("x").build())));
            x.addField(new Field("sparse_tensor",
                                 new TensorDataType(new TensorType.Builder().mapped("x").mapped("y").build())));
            x.addField(new Field("dense_tensor",
                    new TensorDataType(new TensorType.Builder().indexed("x", 2).indexed("y", 3).build())));
            x.addField(new Field("dense_int8_tensor",
                    new TensorDataType(TensorType.fromSpec("tensor<int8>(x[2],y[3])"))));
            x.addField(new Field("dense_float_tensor",
                    new TensorDataType(TensorType.fromSpec("tensor<float>(y[3])"))));
            x.addField(new Field("dense_unbound_tensor",
                    new TensorDataType(new TensorType.Builder().indexed("x").indexed("y").build())));
            x.addField(new Field("mixed_tensor",
                    new TensorDataType(new TensorType.Builder().mapped("x").indexed("y", 3).build())));
            x.addField(new Field("mixed_bfloat16_tensor",
                    new TensorDataType(TensorType.fromSpec("tensor<bfloat16>(x{},y[3])"))));
            x.addField(new Field("mixed_tensor_adv",
                    new TensorDataType(new TensorType.Builder().mapped("x").mapped("y").mapped("z").indexed("a", 3).build())));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testpredicate");
            x.addField(new Field("boolean", DataType.PREDICATE));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testint");
            x.addField(new Field("integerfield", DataType.INT));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testnull");
            x.addField(new Field("intfield", DataType.INT));
            x.addField(new Field("stringfield", DataType.STRING));
            x.addField(new Field("arrayfield", new ArrayDataType(DataType.STRING)));
            x.addField(new Field("weightedsetfield", new WeightedSetDataType(DataType.STRING, true, true)));
            x.addField(new Field("mapfield",  new MapDataType(DataType.STRING, DataType.STRING)));
            x.addField(new Field("tensorfield", new TensorDataType(new TensorType.Builder().indexed("x").build())));
            types.registerDocumentType(x);
        }
    }

    @After
    public void tearDown() throws Exception {
        types = null;
        parserFactory = null;
    }

    private JsonReader createReader(String jsonInput) {
        InputStream input = new ByteArrayInputStream(Utf8.toBytes(jsonInput));
        return new JsonReader(types, input, parserFactory);
    }

    @Test
    public void readDocumentWithMissingFieldsField() {
        assertEquals("document is missing the required \"fields\" field",
                     assertThrows(IllegalArgumentException.class,
                                  () -> createReader("{ }").readSingleDocumentStreaming(DocumentOperationType.PUT,
                                                                                        "id:unittest:testnull::whee"))
                             .getMessage());
    }

    @Test
    public void readSingleDocumentsPutStreaming() throws IOException {
        String json = """
                      {
                        "remove": "id:unittest:smoke::ignored",
                        "ignored-extra-array": [{ "foo": null }, { }],
                        "ignored-extra-object": { "foo": [null, { }], "bar": { } },
                        "fields": {
                          "something": "smoketest",
                          "flag": true,
                          "nalle": "bamse"
                        },
                        "id": "id:unittest:smoke::ignored",
                        "create": false,
                        "condition": "true"
                      }
                      """;
        ParsedDocumentOperation operation = createReader(json).readSingleDocumentStreaming(DocumentOperationType.PUT,"id:unittest:smoke::doc1");
        DocumentPut put = ((DocumentPut) operation.operation());
        assertFalse(put.getCreateIfNonExistent());
        assertEquals("true", put.getCondition().getSelection());
        smokeTestDoc(put.getDocument());
    }

    @Test
    public void readSingleDocumentsUpdateStreaming() throws IOException {
        String json = """
                      {
                        "remove": "id:unittest:smoke::ignored",
                        "ignored-extra-array": [{ "foo": null }, { }],
                        "ignored-extra-object": { "foo": [null, { }], "bar": { } },
                        "fields": {
                          "something": { "assign": "smoketest" },
                          "flag": { "assign": true },
                          "nalle": { "assign": "bamse" }
                        },
                        "id": "id:unittest:smoke::ignored",
                        "create": true,
                        "condition": "false"
                      }
                      """;
        ParsedDocumentOperation operation = createReader(json).readSingleDocumentStreaming(DocumentOperationType.UPDATE,"id:unittest:smoke::doc1");
        Document doc = new Document(types.getDocumentType("smoke"), new DocumentId("id:unittest:smoke::doc1"));
        DocumentUpdate update = ((DocumentUpdate) operation.operation());
        update.applyTo(doc);
        smokeTestDoc(doc);
        assertTrue(update.getCreateIfNonExistent());
        assertEquals("false", update.getCondition().getSelection());
    }

    @Test
    public void readSingleDocumentPut() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:smoke::doc1",
                                     "fields": {
                                       "something": "smoketest",
                                       "flag": true,
                                       "nalle": "bamse"
                                     }
                                   }
                                   """);
        smokeTestDoc(doc);
    }

    @Test
    public final void readSingleDocumentUpdate() throws IOException {
        DocumentUpdate doc = parseUpdate("""
                                         {
                                           "update": "id:unittest:smoke::whee",
                                           "fields": {
                                             "something": {
                                               "assign": "orOther"
                                             }
                                           }
                                         }
                                         """);
        FieldUpdate f = doc.getFieldUpdate("something");
        assertEquals(1, f.size());
        assertTrue(f.getValueUpdate(0) instanceof AssignValueUpdate);
        assertEquals(new StringFieldValue("orOther"), f.getValueUpdate(0).getValue());
    }

    @Test
    public void readClearField() throws IOException {
        DocumentUpdate doc = parseUpdate("""
                                         {
                                           "update": "id:unittest:smoke::whee",
                                           "fields": {
                                             "int1": {
                                               "assign": null
                                             }
                                           }
                                         }
                                         """);
        FieldUpdate f = doc.getFieldUpdate("int1");
        assertEquals(1, f.size());
        assertTrue(f.getValueUpdate(0) instanceof ClearValueUpdate);
        assertNull(f.getValueUpdate(0).getValue());
    }

    @Test
    public void smokeTest() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:smoke::doc1",
                                     "fields": {
                                       "something": "smoketest",
                                       "flag": true,
                                       "nalle": "bamse"
                                     }
                                   }
                                   """);
        smokeTestDoc(doc);
    }

    @Test
    public void docIdLookaheadTest() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:smoke::doc1",
                                     "fields": {
                                       "something": "smoketest",
                                       "flag": true,
                                       "nalle": "bamse"
                                     }
                                   }
                                   """);
        smokeTestDoc(doc);
    }

    @Test
    public void emptyDocTest() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:smoke::whee",
                                     "fields": { }
                                   }""");
        assertEquals(new Document(types.getDocumentType("smoke"), new DocumentId("id:unittest:smoke::whee")),
                     doc);
    }

    @Test
    public void testStruct() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:mirrors::whee",
                                     "fields": {
                                       "skuggsjaa": {
                                         "sandra": "person",
                                         "cloud": "another person"
                                       }
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("skuggsjaa"));
        assertSame(Struct.class, f.getClass());
        Struct s = (Struct) f;
        assertEquals("person", ((StringFieldValue) s.getFieldValue("sandra")).getString());
    }

    private DocumentUpdate parseUpdate(String json) throws IOException {
        InputStream rawDoc = new ByteArrayInputStream(Utf8.toBytes(json));
        JsonReader r = new JsonReader(types, rawDoc, parserFactory);
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentUpdate update = new DocumentUpdate(docType, parseInfo.documentId);
        new VespaJsonDocumentReader(false).readUpdate(parseInfo.fieldsBuffer, update);
        return update;
    }

    @Test
    public void testStructUpdate() throws IOException {
        DocumentUpdate put = parseUpdate("""
                                         {
                                           "update": "id:unittest:mirrors:g=test:whee",
                                           "create": true,
                                           "fields": {
                                             "skuggsjaa": {
                                               "assign": {
                                                 "sandra": "person",
                                                 "cloud": "another person"
                                               }
                                             }
                                           }
                                         }
                                         """);
        assertEquals(1, put.fieldUpdates().size());
        FieldUpdate fu = put.fieldUpdates().iterator().next();
        assertEquals(1, fu.getValueUpdates().size());
        ValueUpdate vu = fu.getValueUpdate(0);
        assertTrue(vu instanceof AssignValueUpdate);
        AssignValueUpdate avu = (AssignValueUpdate) vu;
        assertTrue(avu.getValue() instanceof Struct);
        Struct s = (Struct) avu.getValue();
        assertEquals(2, s.getFieldCount());
        assertEquals(new StringFieldValue("person"), s.getFieldValue(s.getField("sandra")));
        GrowableByteBuffer buf = new GrowableByteBuffer();
        DocumentSerializer serializer = DocumentSerializerFactory.createHead(buf);
        put.serialize(serializer);
        assertEquals(107, buf.position());
    }

    @Test
    public final void testEmptyStructUpdate() throws IOException {
        DocumentUpdate put = parseUpdate("""
                                         {
                                           "update": "id:unittest:mirrors:g=test:whee",
                                           "create": true,
                                           "fields": {
                                             "skuggsjaa": {
                                               "assign": { }
                                             }
                                           }
                                         }
                                         """);
        assertEquals(1, put.fieldUpdates().size());
        FieldUpdate fu = put.fieldUpdates().iterator().next();
        assertEquals(1, fu.getValueUpdates().size());
        ValueUpdate vu = fu.getValueUpdate(0);
        assertTrue(vu instanceof AssignValueUpdate);
        AssignValueUpdate avu = (AssignValueUpdate) vu;
        assertTrue(avu.getValue() instanceof Struct);
        Struct s = (Struct) avu.getValue();
        assertEquals(0, s.getFieldCount());
        GrowableByteBuffer buf = new GrowableByteBuffer();
        DocumentSerializer serializer = DocumentSerializerFactory.createHead(buf);
        put.serialize(serializer);
        assertEquals(69, buf.position());
    }

    @Test
    public void testUpdateArray() throws IOException {
        DocumentUpdate doc = parseUpdate("""
                                         {
                                           "update": "id:unittest:testarray::whee",
                                           "fields": {
                                             "actualarray": {
                                               "add": [
                                                 "person",
                                                 "another person"
                                               ]
                                             }
                                           }
                                         }
                                         """);
        checkSimpleArrayAdd(doc);
    }

    @Test
    public void testUpdateWeighted() throws IOException {
        DocumentUpdate doc = parseUpdate("""
                                         {
                                           "update": "id:unittest:testset::whee",
                                           "fields": {
                                             "actualset": {
                                               "add": {
                                                 "person": 37,
                                                 "another person": 41
                                               }
                                             }
                                           }
                                         }
                                         """);

        Map<String, Integer> weights = new HashMap<>();
        FieldUpdate x = doc.getFieldUpdate("actualset");
        for (ValueUpdate<?> v : x.getValueUpdates()) {
            AddValueUpdate adder = (AddValueUpdate) v;
            final String s = ((StringFieldValue) adder.getValue()).getString();
            weights.put(s, adder.getWeight());
        }
        assertEquals(2, weights.size());
        final String o = "person";
        final String o2 = "another person";
        assertTrue(weights.containsKey(o));
        assertTrue(weights.containsKey(o2));
        assertEquals(Integer.valueOf(37), weights.get(o));
        assertEquals(Integer.valueOf(41), weights.get(o2));
    }

    @Test
    public void testUpdateMatch() throws IOException {
        DocumentUpdate doc = parseUpdate("""
                                         {
                                           "update": "id:unittest:testset::whee",
                                           "fields": {
                                             "actualset": {
                                               "match": {
                                                 "element": "person",
                                                 "increment": 13
                                               }
                                             }
                                           }
                                         }
                                         """);

        DocumentUpdate otherDoc = parseUpdate("""
                                              {
                                                "update": "id:unittest:testset::whee",
                                                "fields": {
                                                  "actualset": {
                                                    "match": {
                                                      "increment": 13,
                                                      "element": "person"
                                                    }
                                                  }
                                                }
                                              }""");

        assertEquals(doc, otherDoc);

        Map<String, Tuple2<Number, String>> matches = new HashMap<>();
        FieldUpdate x = doc.getFieldUpdate("actualset");
        for (ValueUpdate<?> v : x.getValueUpdates()) {
            MapValueUpdate adder = (MapValueUpdate) v;
            final String key = ((StringFieldValue) adder.getValue())
                    .getString();
            String op = ((ArithmeticValueUpdate) adder.getUpdate())
                    .getOperator().toString();
            Number n = ((ArithmeticValueUpdate) adder.getUpdate()).getOperand();
            matches.put(key, new Tuple2<>(n, op));
        }
        assertEquals(1, matches.size());
        final String o = "person";
        assertEquals("ADD", matches.get(o).second);
        assertEquals(Double.valueOf(13), matches.get(o).first);
    }

    @SuppressWarnings({ "cast", "unchecked", "rawtypes" })
    @Test
    public void testArithmeticOperators() throws IOException {
        Tuple2[] operations = new Tuple2[] {
                new Tuple2<>(UPDATE_DECREMENT,
                             ArithmeticValueUpdate.Operator.SUB),
                new Tuple2<>(UPDATE_DIVIDE,
                        ArithmeticValueUpdate.Operator.DIV),
                new Tuple2<>(UPDATE_INCREMENT,
                        ArithmeticValueUpdate.Operator.ADD),
                new Tuple2<>(UPDATE_MULTIPLY,
                        ArithmeticValueUpdate.Operator.MUL) };
        for (Tuple2<String, Operator> operator : operations) {
            DocumentUpdate doc = parseUpdate("""
                                             {
                                               "update": "id:unittest:testset::whee",
                                               "fields": {
                                                 "actualset": {
                                                   "match": {
                                                     "element": "person",
                                                     "%s": 13
                                                   }
                                                 }
                                               }
                                             }
                                             """.formatted(operator.first));

            Map<String, Tuple2<Number, Operator>> matches = new HashMap<>();
            FieldUpdate x = doc.getFieldUpdate("actualset");
            for (ValueUpdate v : x.getValueUpdates()) {
                MapValueUpdate adder = (MapValueUpdate) v;
                final String key = ((StringFieldValue) adder.getValue())
                        .getString();
                Operator op = ((ArithmeticValueUpdate) adder
                        .getUpdate()).getOperator();
                Number n = ((ArithmeticValueUpdate) adder.getUpdate())
                        .getOperand();
                matches.put(key, new Tuple2<>(n, op));
            }
            assertEquals(1, matches.size());
            final String o = "person";
            assertSame(operator.second, matches.get(o).second);
            assertEquals(Double.valueOf(13), matches.get(o).first);
        }
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void testArrayIndexing() throws IOException {
        DocumentUpdate doc = parseUpdate("""
                                         {
                                           "update": "id:unittest:testarray::whee",
                                           "fields": {
                                             "actualarray": {
                                               "match": {
                                                 "element": 3,
                                                 "assign": "nalle"
                                               }
                                             }
                                           }
                                         }
                                         """);

        Map<Number, String> matches = new HashMap<>();
        FieldUpdate x = doc.getFieldUpdate("actualarray");
        for (ValueUpdate v : x.getValueUpdates()) {
            MapValueUpdate adder = (MapValueUpdate) v;
            final Number key = ((IntegerFieldValue) adder.getValue())
                    .getNumber();
            String op = ((StringFieldValue) adder.getUpdate()
                    .getValue()).getString();
            matches.put(key, op);
        }
        assertEquals(1, matches.size());
        Number n = Integer.valueOf(3);
        assertEquals("nalle", matches.get(n));
    }

    @Test
    public void testDocumentRemove() {
        JsonReader r = createReader(inputJson("{'remove': 'id:unittest:smoke::whee'}"));
        DocumentType docType = r.readDocumentType(new DocumentId("id:unittest:smoke::whee"));
        assertEquals("smoke", docType.getName());
    }

    private Document docFromJson(String json) throws IOException {
        JsonReader r = createReader(json);
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        new VespaJsonDocumentReader(false).readPut(parseInfo.fieldsBuffer, put);
        return put.getDocument();
    }

    @Test
    public void testWeightedSet() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testset::whee",
                                     "fields": {
                                       "actualset": {
                                         "nalle": 2,
                                         "tralle": 7
                                       }
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("actualset"));
        assertSame(WeightedSet.class, f.getClass());
        WeightedSet<?> w = (WeightedSet<?>) f;
        assertEquals(2, w.size());
        assertEquals(Integer.valueOf(2), w.get(new StringFieldValue("nalle")));
        assertEquals(Integer.valueOf(7), w.get(new StringFieldValue("tralle")));
    }

    @Test
    public void testArray() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testarray::whee",
                                     "fields": {
                                       "actualarray": [
                                         "nalle",
                                         "tralle"
                                       ]
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("actualarray"));
        assertSame(Array.class, f.getClass());
        Array<?> a = (Array<?>) f;
        assertEquals(2, a.size());
        assertEquals(new StringFieldValue("nalle"), a.get(0));
        assertEquals(new StringFieldValue("tralle"), a.get(1));
    }

    @Test
    public void testMap() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testmap::whee",
                                     "fields": {
                                       "actualmap": {
                                         "nalle": "kalle",
                                         "tralle": "skalle"
                                       }
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("actualmap"));
        assertSame(MapFieldValue.class, f.getClass());
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) f;
        assertEquals(2, m.size());
        assertEquals(new StringFieldValue("kalle"), m.get(new StringFieldValue("nalle")));
        assertEquals(new StringFieldValue("skalle"), m.get(new StringFieldValue("tralle")));
    }

    @Test
    public void testOldMap() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testmap::whee",
                                     "fields": {
                                       "actualmap": [
                                         {
                                           "key": "nalle",
                                           "value": "kalle"
                                         },
                                         {
                                           "key": "tralle",
                                           "value": "skalle"
                                         }
                                       ]
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("actualmap"));
        assertSame(MapFieldValue.class, f.getClass());
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) f;
        assertEquals(2, m.size());
        assertEquals(new StringFieldValue("kalle"), m.get(new StringFieldValue("nalle")));
        assertEquals(new StringFieldValue("skalle"), m.get(new StringFieldValue("tralle")));
    }

    @Test
    public void testPositionPositive() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testsinglepos::bamf",
                                     "fields": {
                                       "singlepos": "N63.429722;E10.393333"
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("singlepos"));
        assertSame(Struct.class, f.getClass());
        assertEquals(10393333, PositionDataType.getXValue(f).getInteger());
        assertEquals(63429722, PositionDataType.getYValue(f).getInteger());
    }

    @Test
    public void testPositionOld() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testsinglepos::bamf",
                                     "fields": {
                                       "singlepos": {
                                         "x": 10393333,
                                         "y": 63429722
                                       }
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("singlepos"));
        assertSame(Struct.class, f.getClass());
        assertEquals(10393333, PositionDataType.getXValue(f).getInteger());
        assertEquals(63429722, PositionDataType.getYValue(f).getInteger());
    }

    @Test
    public void testGeoPosition() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testsinglepos::bamf",
                                     "fields": {
                                       "singlepos": {
                                         "lat": 63.429722,
                                         "lng": 10.393333
                                       }
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("singlepos"));
        assertSame(Struct.class, f.getClass());
        assertEquals(10393333, PositionDataType.getXValue(f).getInteger());
        assertEquals(63429722, PositionDataType.getYValue(f).getInteger());
    }

    @Test
    public void testGeoPositionNoAbbreviations() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testsinglepos::bamf",
                                     "fields": {
                                       "singlepos": {
                                         "latitude": 63.429722,
                                         "longitude": 10.393333
                                       }
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("singlepos"));
        assertSame(Struct.class, f.getClass());
        assertEquals(10393333, PositionDataType.getXValue(f).getInteger());
        assertEquals(63429722, PositionDataType.getYValue(f).getInteger());
    }

    @Test
    public void testPositionGeoPos() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testsinglepos::bamf",
                                     "fields": {
                                       "geopos": "N63.429722;E10.393333"
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("geopos"));
        assertSame(Struct.class, f.getClass());
        assertEquals(10393333, PositionDataType.getXValue(f).getInteger());
        assertEquals(63429722, PositionDataType.getYValue(f).getInteger());
        assertEquals(f.getDataType(), PositionDataType.INSTANCE);
    }

    @Test
    public void testPositionOldGeoPos() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testsinglepos::bamf",
                                     "fields": {
                                       "geopos": {
                                         "x": 10393333,
                                         "y": 63429722
                                       }
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("geopos"));
        assertSame(Struct.class, f.getClass());
        assertEquals(10393333, PositionDataType.getXValue(f).getInteger());
        assertEquals(63429722, PositionDataType.getYValue(f).getInteger());
        assertEquals(f.getDataType(), PositionDataType.INSTANCE);
    }

    @Test
    public void testGeoPositionGeoPos() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testsinglepos::bamf",
                                     "fields": {
                                       "geopos": {
                                         "lat": 63.429722,
                                         "lng": 10.393333
                                       }
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("geopos"));
        assertSame(Struct.class, f.getClass());
        assertEquals(10393333, PositionDataType.getXValue(f).getInteger());
        assertEquals(63429722, PositionDataType.getYValue(f).getInteger());
        assertEquals(f.getDataType(), PositionDataType.INSTANCE);
        assertEquals(PositionDataType.INSTANCE, f.getDataType());
    }

    @Test
    public void testPositionNegative() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testsinglepos::bamf",
                                     "fields": {
                                       "singlepos": "W46.63;S23.55"
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue(doc.getField("singlepos"));
        assertSame(Struct.class, f.getClass());
        assertEquals(-46630000, PositionDataType.getXValue(f).getInteger());
        assertEquals(-23550000, PositionDataType.getYValue(f).getInteger());
    }

    @Test
    public void testRaw() throws IOException {
        String base64 = new String(new JsonStringEncoder().quoteAsString(
                Base64.getEncoder().withoutPadding().encodeToString(Utf8.toBytes("smoketest"))));
        String s = fieldStringFromBase64RawContent(base64);
        assertEquals("smoketest", s);
    }

    @Test
    public void can_read_legacy_chunked_base64_raw_field_encoding() throws IOException {
        String expected = "this is a string with an impressive length. it's long enough to reach the end of the line, wow!";
        String base64withDelims = "dGhpcyBpcyBhIHN0cmluZyB3aXRoIGFuIGltcHJlc3NpdmUgbGVuZ3RoLiBpdCdzIGxvbmcgZW5v\\r\\n" +
                "dWdoIHRvIHJlYWNoIHRoZSBlbmQgb2YgdGhlIGxpbmUsIHdvdyE=\\r\\n";
        assertEquals(expected, fieldStringFromBase64RawContent(base64withDelims));
    }

    private String fieldStringFromBase64RawContent(String base64data) throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testraw::whee",
                                     "fields": {
                                       "actualraw": "%s"
                                     }
                                   }
                                   """.formatted(base64data));
        FieldValue f = doc.getFieldValue(doc.getField("actualraw"));
        assertSame(Raw.class, f.getClass());
        Raw s = (Raw) f;
        return Utf8.toString(s.getByteBuffer());
    }

    @Test
    public void testMapStringToArrayOfInt() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testMapStringToArrayOfInt::whee",
                                     "fields": {
                                       "actualMapStringToArrayOfInt": {
                                         "bamse": [1, 2, 3]
                                       }
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue("actualMapStringToArrayOfInt");
        assertSame(MapFieldValue.class, f.getClass());
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) f;
        Array<?> a = (Array<?>) m.get(new StringFieldValue("bamse"));
        assertEquals(3, a.size());
        assertEquals(new IntegerFieldValue(1), a.get(0));
        assertEquals(new IntegerFieldValue(2), a.get(1));
        assertEquals(new IntegerFieldValue(3), a.get(2));
    }

    @Test
    public void testOldMapStringToArrayOfInt() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testMapStringToArrayOfInt::whee",
                                     "fields": {
                                       "actualMapStringToArrayOfInt": [
                                         {
                                           "key": "bamse",
                                           "value": [1, 2, 3]
                                         }
                                       ]
                                     }
                                   }
                                   """);
        FieldValue f = doc.getFieldValue("actualMapStringToArrayOfInt");
        assertSame(MapFieldValue.class, f.getClass());
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) f;
        Array<?> a = (Array<?>) m.get(new StringFieldValue("bamse"));
        assertEquals(3, a.size());
        assertEquals(new IntegerFieldValue(1), a.get(0));
        assertEquals(new IntegerFieldValue(2), a.get(1));
        assertEquals(new IntegerFieldValue(3), a.get(2));
    }

    @Test
    public void testAssignToString() throws IOException {
        DocumentUpdate doc = parseUpdate("""
                                         {
                                           "update": "id:unittest:smoke::whee",
                                           "fields": {
                                             "something": {
                                               "assign": "orOther"
                                             }
                                           }
                                         }
                                         """);
        FieldUpdate f = doc.getFieldUpdate("something");
        assertEquals(1, f.size());
        AssignValueUpdate a = (AssignValueUpdate) f.getValueUpdate(0);
        assertEquals(new StringFieldValue("orOther"), a.getValue());
    }

    @Test
    public void testNestedArrayMatch() throws IOException {
        DocumentUpdate nested = parseUpdate("""
                                            {
                                              "update": "id:unittest:testArrayOfArrayOfInt::whee",
                                              "fields": {
                                                "arrayOfArrayOfInt": {
                                                  "match": {
                                                    "element": 1,
                                                    "match": {
                                                      "element": 2,
                                                      "assign": 3
                                                    }
                                                  }
                                                }
                                              }
                                            }
                                            """);

        DocumentUpdate equivalent = parseUpdate("""
                                                {
                                                  "update": "id:unittest:testArrayOfArrayOfInt::whee",
                                                  "fields": {
                                                    "arrayOfArrayOfInt": {
                                                      "match": {
                                                        "match": {
                                                          "assign": 3,
                                                          "element": 2
                                                        },
                                                        "element": 1
                                                      }
                                                    }
                                                  }
                                                }
                                                """);

        assertEquals(nested, equivalent);
        assertEquals(1, nested.fieldUpdates().size());
        FieldUpdate fu = nested.fieldUpdates().iterator().next();
        assertEquals(1, fu.getValueUpdates().size());
        MapValueUpdate mvu = (MapValueUpdate) fu.getValueUpdate(0);
        assertEquals(new IntegerFieldValue(1), mvu.getValue());
        MapValueUpdate nvu = (MapValueUpdate) mvu.getUpdate();
        assertEquals(new IntegerFieldValue(2), nvu.getValue());
        AssignValueUpdate avu = (AssignValueUpdate) nvu.getUpdate();
        assertEquals(new IntegerFieldValue(3), avu.getValue());

        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testArrayOfArrayOfInt::whee",
                                     "fields": {
                                       "arrayOfArrayOfInt": [
                                         [1, 2, 3],
                                         [4, 5, 6]
                                       ]
                                     }
                                   }
                                   """);
        nested.applyTo(doc);
        Document expected = docFromJson("""
                                        {
                                          "put": "id:unittest:testArrayOfArrayOfInt::whee",
                                          "fields": {
                                            "arrayOfArrayOfInt": [
                                              [1, 2, 3],
                                              [4, 5, 3]
                                            ]
                                          }
                                        }
                                        """);
        assertEquals(expected, doc);
    }

    @Test
    public void testMatchCannotUpdateNestedFields() {
        // Should this work? It doesn't.
        assertEquals("Field type Map<string,Array<int>> not supported.",
                     assertThrows(UnsupportedOperationException.class,
                                  () -> parseUpdate("""
                                                    {
                                                      "update": "id:unittest:testMapStringToArrayOfInt::whee",
                                                      "fields": {
                                                        "actualMapStringToArrayOfInt": {
                                                          "match": {
                                                            "element": "bamse",
                                                            "match": {
                                                              "element": 1,
                                                              "assign": 4
                                                            }
                                                          }
                                                        }
                                                      }
                                                    }
                                                    """)).getMessage());
    }

    @Test
    public void testMatchCannotAssignToNestedMap() {
        // Unsupported value type for map value assign.
        assertEquals("Field type Map<string,Array<int>> not supported.",
                     assertThrows(UnsupportedOperationException.class,
                                  () -> parseUpdate("""
                                                    {
                                                      "update": "id:unittest:testMapStringToArrayOfInt::whee",
                                                      "fields": {
                                                        "actualMapStringToArrayOfInt": {
                                                          "match": {
                                                            "element": "bamse",
                                                            "assign": [1, 3, 4]
                                                          }
                                                        }
                                                      }
                                                    }
                                                    """)).getMessage());
    }

    @Test
    public void testMatchCannotAssignToMap() {
        // Unsupported value type for map value assign.
        assertEquals("Field type Map<string,string> not supported.",
                     assertThrows(UnsupportedOperationException.class,
                                  () -> parseUpdate("""
                                                    {
                                                      "update": "id:unittest:testmap::whee",
                                                      "fields": {
                                                        "actualmap": {
                                                          "match": {
                                                            "element": "bamse",
                                                            "assign": "bar"
                                                          }
                                                        }
                                                      }
                                                    }
                                                    """)).getMessage());
    }



    @Test
    public void testAssignInsideArrayInMap() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testMapStringToArrayOfInt::whee",
                                     "fields": {
                                       "actualMapStringToArrayOfInt": {
                                         "bamse": [1, 2, 3]
                                       }
                                     }
                                   }""");

        assertEquals(2, ((MapFieldValue<StringFieldValue, Array<IntegerFieldValue>>) doc.getFieldValue("actualMapStringToArrayOfInt"))
                .get(StringFieldValue.getFactory().create("bamse")).get(1).getInteger());

        DocumentUpdate update = parseUpdate("""
                                            {
                                              "update": "id:unittest:testMapStringToArrayOfInt::whee",
                                              "fields": {
                                                "actualMapStringToArrayOfInt{bamse}[1]": {
                                                  "assign": 4
                                                }
                                              }
                                            }
                                            """);
        assertEquals(1, update.fieldPathUpdates().size());

        update.applyTo(doc);
        assertEquals(4, ((MapFieldValue<StringFieldValue, Array<IntegerFieldValue>>) doc.getFieldValue("actualMapStringToArrayOfInt"))
                .get(StringFieldValue.getFactory().create("bamse")).get(1).getInteger());
    }

    @Test
    public void testAssignToArray() throws IOException {
        DocumentUpdate doc = parseUpdate("""
                                         {
                                           "update": "id:unittest:testMapStringToArrayOfInt::whee",
                                           "fields": {
                                             "actualMapStringToArrayOfInt": {
                                               "assign": {
                                                 "bamse": [1, 2, 3]
                                               }
                                             }
                                           }
                                         }
                                         """);
        FieldUpdate f = doc.getFieldUpdate("actualMapStringToArrayOfInt");
        assertEquals(1, f.size());
        AssignValueUpdate assign = (AssignValueUpdate) f.getValueUpdate(0);
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) assign.getValue();
        Array<?> a = (Array<?>) m.get(new StringFieldValue("bamse"));
        assertEquals(3, a.size());
        assertEquals(new IntegerFieldValue(1), a.get(0));
        assertEquals(new IntegerFieldValue(2), a.get(1));
        assertEquals(new IntegerFieldValue(3), a.get(2));
    }

    @Test
    public void testOldAssignToArray() throws IOException {
        DocumentUpdate doc = parseUpdate("""
                                         {
                                           "update": "id:unittest:testMapStringToArrayOfInt::whee",
                                           "fields": {
                                             "actualMapStringToArrayOfInt": {
                                               "assign": [
                                                 {
                                                   "key": "bamse",
                                                   "value": [1, 2, 3]
                                                 }
                                               ]
                                             }
                                           }
                                         }
                                         """);
        FieldUpdate f = doc.getFieldUpdate("actualMapStringToArrayOfInt");
        assertEquals(1, f.size());
        AssignValueUpdate assign = (AssignValueUpdate) f.getValueUpdate(0);
        MapFieldValue<?, ?> m = (MapFieldValue<?, ?>) assign.getValue();
        Array<?> a = (Array<?>) m.get(new StringFieldValue("bamse"));
        assertEquals(3, a.size());
        assertEquals(new IntegerFieldValue(1), a.get(0));
        assertEquals(new IntegerFieldValue(2), a.get(1));
        assertEquals(new IntegerFieldValue(3), a.get(2));
    }

    @Test
    public void testAssignToWeightedSet() throws IOException {
        DocumentUpdate doc = parseUpdate("""
                                         {
                                           "update": "id:unittest:testset::whee",
                                           "fields": {
                                             "actualset": {
                                               "assign": {
                                                 "person": 37,
                                                 "another person": 41
                                               }
                                             }
                                           }
                                         }
                                         """);
        FieldUpdate x = doc.getFieldUpdate("actualset");
        assertEquals(1, x.size());
        AssignValueUpdate assign = (AssignValueUpdate) x.getValueUpdate(0);
        WeightedSet<?> w = (WeightedSet<?>) assign.getValue();
        assertEquals(2, w.size());
        assertEquals(Integer.valueOf(37), w.get(new StringFieldValue("person")));
        assertEquals(Integer.valueOf(41), w.get(new StringFieldValue("another person")));
    }


    @Test
    public void testCompleteFeed() {
        JsonReader r = createReader("""
                                    [
                                      {
                                        "put": "id:unittest:smoke::whee",
                                        "fields": {
                                          "something": "smoketest",
                                          "flag": true,
                                          "nalle": "bamse"
                                        }
                                      },
                                      {
                                        "update": "id:unittest:testarray::whee",
                                        "fields": {
                                          "actualarray": {
                                            "add": [
                                              "person",
                                              "another person"
                                            ]
                                          }
                                        }
                                      },
                                      {
                                        "remove": "id:unittest:smoke::whee"
                                      }
                                    ]
                                    """);

        controlBasicFeed(r);
    }

    @Test
    public void testCompleteFeedWithCreateAndCondition() {
        JsonReader r = createReader("""
                                    [
                                      {
                                        "put": "id:unittest:smoke::whee",
                                        "fields": {
                                          "something": "smoketest",
                                          "flag": true,
                                          "nalle": "bamse"
                                        }
                                      },
                                      {
                                        "condition":"bla",
                                        "update": "id:unittest:testarray::whee",
                                        "create":true,
                                        "fields": {
                                          "actualarray": {
                                            "add": [
                                              "person",
                                              "another person"
                                            ]
                                          }
                                        }
                                      },
                                      {
                                        "remove": "id:unittest:smoke::whee"
                                      }
                                    ]
                                    """);

        DocumentOperation d = r.next();
        Document doc = ((DocumentPut) d).getDocument();
        smokeTestDoc(doc);

        d = r.next();
        DocumentUpdate update = (DocumentUpdate) d;
        checkSimpleArrayAdd(update);
        assertTrue(update.getCreateIfNonExistent());
        assertEquals("bla", update.getCondition().getSelection());

        d = r.next();
        DocumentRemove remove = (DocumentRemove) d;
        assertEquals("smoke", remove.getId().getDocType());

        assertNull(r.next());
    }

    @Test
    public void testUpdateWithConditionAndCreateInDifferentOrdering() {
        int documentsCreated = 106;
        List<String> parts = Arrays.asList(
                "\"condition\":\"bla\"",
                "\"update\": \"id:unittest:testarray::whee\"",
                " \"fields\": { " + "\"actualarray\": { \"add\": [" + " \"person\",\"another person\"]}}",
                " \"create\":true");
        Random random = new Random(42);
        StringBuilder documents = new StringBuilder("[");
        for (int x = 0; x < documentsCreated; x++) {
            Collections.shuffle(parts, random);
            documents.append("{").append(Joiner.on(",").join(parts)).append("}");
            if (x < documentsCreated -1) {
                documents.append(",");
            }
        }
        documents.append("]");
        InputStream rawDoc = new ByteArrayInputStream(Utf8.toBytes(documents.toString()));

        JsonReader r = new JsonReader(types, rawDoc, parserFactory);

        for (int x = 0; x < documentsCreated; x++) {
            DocumentUpdate update = (DocumentUpdate) r.next();
            checkSimpleArrayAdd(update);
            assertTrue(update.getCreateIfNonExistent());
            assertEquals("bla", update.getCondition().getSelection());
        }

        assertNull(r.next());
    }


    @Test
    public void testCreateIfNonExistentInPut() {
        JsonReader r = createReader("""
                                    [
                                      {
                                        "create":true,
                                        "fields": {
                                          "something": "smoketest",
                                          "nalle": "bamse"
                                        },
                                        "put": "id:unittest:smoke::whee"
                                      }
                                    ]
                                    """);
        var op = r.next();
        var put = (DocumentPut) op;
        assertTrue(put.getCreateIfNonExistent());
    }

    @Test
    public void testCompleteFeedWithIdAfterFields() {
        JsonReader r = createReader("""
                                    [
                                      {
                                        "fields": {
                                          "something": "smoketest",
                                          "flag": true,
                                          "nalle": "bamse"
                                        },
                                        "put": "id:unittest:smoke::whee"
                                      },
                                      {
                                        "fields": {
                                          "actualarray": {
                                            "add": [
                                              "person",
                                              "another person"
                                            ]
                                          }
                                        },
                                        "update": "id:unittest:testarray::whee"
                                      },
                                      {
                                        "remove": "id:unittest:smoke::whee"
                                      }
                                    ]
                                    """);

        controlBasicFeed(r);
    }

    protected void controlBasicFeed(JsonReader r) {
        DocumentOperation d = r.next();
        Document doc = ((DocumentPut) d).getDocument();
        smokeTestDoc(doc);

        d = r.next();
        DocumentUpdate update = (DocumentUpdate) d;
        checkSimpleArrayAdd(update);

        d = r.next();
        DocumentRemove remove = (DocumentRemove) d;
        assertEquals("smoke", remove.getId().getDocType());

        assertNull(r.next());
    }


    @Test
    public void testCompleteFeedWithEmptyDoc() {
        JsonReader r = createReader("""
                                    [
                                      {
                                        "put": "id:unittest:smoke::whee",
                                        "fields": {}
                                      },
                                      {
                                        "update": "id:unittest:testarray::whee",
                                        "fields": {}
                                      },
                                      {
                                        "remove": "id:unittest:smoke::whee"
                                      }
                                    ]
                                    """);

        DocumentOperation d = r.next();
        Document doc = ((DocumentPut) d).getDocument();
        assertEquals("smoke", doc.getId().getDocType());

        d = r.next();
        DocumentUpdate update = (DocumentUpdate) d;
        assertEquals("testarray", update.getId().getDocType());

        d = r.next();
        DocumentRemove remove = (DocumentRemove) d;
        assertEquals("smoke", remove.getId().getDocType());

        assertNull(r.next());

    }

    private void checkSimpleArrayAdd(DocumentUpdate update) {
        Set<String> toAdd = new HashSet<>();
        FieldUpdate x = update.getFieldUpdate("actualarray");
        for (ValueUpdate<?> v : x.getValueUpdates()) {
            AddValueUpdate adder = (AddValueUpdate) v;
            toAdd.add(((StringFieldValue) adder.getValue()).getString());
        }
        assertEquals(2, toAdd.size());
        assertTrue(toAdd.contains("person"));
        assertTrue(toAdd.contains("another person"));
    }

    private void smokeTestDoc(Document doc) {
        FieldValue boolField = doc.getFieldValue(doc.getField("flag"));
        assertSame(BoolFieldValue.class, boolField.getClass());
        assertTrue((Boolean)boolField.getWrappedValue());

        FieldValue stringField = doc.getFieldValue(doc.getField("nalle"));
        assertSame(StringFieldValue.class, stringField.getClass());
        assertEquals("bamse", ((StringFieldValue) stringField).getString());
    }

    @Test
    public void nonExistingFieldCausesException() throws IOException {
        Exception expected = assertThrows(IllegalArgumentException.class,
                                          () -> docFromJson("""
                                                            {
                                                              "put": "id:unittest:smoke::whee",
                                                              "fields": {
                                                                "smething": "smoketest",
                                                                "nalle": "bamse"
                                                              }
                                                            }
                                                            """));
        assertTrue(expected.getMessage().startsWith("No field 'smething' in the structure of type 'smoke'"));
    }

    @Test
    public void nonExistingFieldsCanBeIgnoredInPut() throws IOException {
        JsonReader r = createReader("""
                                    {
                                      "put": "id:unittest:smoke::doc1",
                                      "fields": {
                                        "nonexisting1": "ignored value",
                                        "field1": "value1",
                                        "nonexisting2": {
                                          "blocks": {
                                            "a": [2.0, 3.0],
                                            "b": [4.0, 5.0]
                                          }
                                        },
                                        "field2": "value2",
                                        "nonexisting3": {
                                          "cells": [
                                            {
                                              "address": {
                                                "x": "x1"
                                              },
                                              "value": 1.0
                                            }
                                          ]
                                        },
                                        "tensor1": {
                                          "cells": {
                                            "x1": 1.0
                                          }
                                        },
                                        "nonexisting4": "ignored value"
                                      }
                                    }
                                    """);
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        boolean fullyApplied = new VespaJsonDocumentReader(true).readPut(parseInfo.fieldsBuffer, put);
        assertFalse(fullyApplied);
        assertNull(put.getDocument().getField("nonexisting1"));
        assertEquals("value1", put.getDocument().getFieldValue("field1").toString());
        assertNull(put.getDocument().getField("nonexisting2"));
        assertEquals("value2", put.getDocument().getFieldValue("field2").toString());
        assertNull(put.getDocument().getField("nonexisting3"));
        assertEquals(Tensor.from("tensor(x{}):{{x:x1}:1.0}"), put.getDocument().getFieldValue("tensor1").getWrappedValue());
        assertNull(put.getDocument().getField("nonexisting4"));
    }

    @Test
    public void nonExistingFieldsCanBeIgnoredInUpdate()  throws IOException{
        JsonReader r = createReader("""
                                    {
                                      "update": "id:unittest:smoke::doc1",
                                      "fields": {
                                        "nonexisting1": { "assign": "ignored value" },
                                        "field1": { "assign": "value1" },
                                        "nonexisting2": {
                                          "assign": {
                                            "blocks": {
                                              "a":[2.0,3.0],
                                              "b":[4.0,5.0]
                                            }
                                          }
                                        },
                                        "field2": { "assign": "value2" },
                                        "nonexisting3": {
                                          "assign" : {
                                            "cells": [{"address": {"x": "x1"}, "value": 1.0}]
                                          }
                                        },
                                        "tensor1": {"assign": { "cells": {"x1": 1.0} } },
                                        "nonexisting4": { "assign": "ignored value" }
                                      }
                                    }
                                    """);
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentUpdate update = new DocumentUpdate(docType, parseInfo.documentId);
        boolean fullyApplied = new VespaJsonDocumentReader(true).readUpdate(parseInfo.fieldsBuffer, update);
        assertFalse(fullyApplied);
        assertNull(update.getFieldUpdate("nonexisting1"));
        assertEquals("value1", update.getFieldUpdate("field1").getValueUpdates().get(0).getValue().getWrappedValue().toString());
        assertNull(update.getFieldUpdate("nonexisting2"));
        assertEquals("value2", update.getFieldUpdate("field2").getValueUpdates().get(0).getValue().getWrappedValue().toString());
        assertNull(update.getFieldUpdate("nonexisting3"));
        assertEquals(Tensor.from("tensor(x{}):{{x:x1}:1.0}"), update.getFieldUpdate("tensor1").getValueUpdates().get(0).getValue().getWrappedValue());
        assertNull(update.getFieldUpdate("nonexisting4"));
    }

    @Test
    public void feedWithBasicErrorTest() {
        JsonReader r = createReader("""
                                    [
                                      {
                                        "put": "id:test:smoke::0",
                                        "fields": {
                                          "something": "foo"
                                        }
                                      },
                                      {
                                        "put": "id:test:smoke::1",
                                        "fields": {
                                          "something": "foo"
                                        }
                                      },
                                      {
                                        "put": "id:test:smoke::2",
                                        "fields": {
                                          "something": "foo"
                                        }
                                      },
                                    ]"""); // Trailing comma in array ...
        assertTrue(assertThrows(RuntimeException.class,
                                () -> { while (r.next() != null); })
                           .getMessage().contains("JsonParseException"));
    }

    @Test
    public void idAsAliasForPutTest()  throws IOException{
        JsonReader r = createReader("""
                                    {
                                      "id": "id:unittest:smoke::doc1",
                                      "fields": {
                                        "something": "smoketest",
                                        "flag": true,
                                        "nalle": "bamse"
                                      }
                                    }
                                    """);
        DocumentParseInfo parseInfo = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(parseInfo.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, parseInfo.documentId));
        boolean fullyApplied = new VespaJsonDocumentReader(false).readPut(parseInfo.fieldsBuffer, put);
        assertTrue(fullyApplied);
        smokeTestDoc(put.getDocument());
    }

    private void testFeedWithTestAndSetCondition(String jsonDoc) {
        ByteArrayInputStream parseInfoDoc = new ByteArrayInputStream(Utf8.toBytes(jsonDoc));
        JsonReader reader = new JsonReader(types, parseInfoDoc, parserFactory);
        int NUM_OPERATIONS_IN_FEED = 3;

        for (int i = 0; i < NUM_OPERATIONS_IN_FEED; i++) {
            DocumentOperation operation = reader.next();

            assertTrue("A test and set condition should be present",
                    operation.getCondition().isPresent());

            assertEquals("DocumentOperation's test and set condition should be equal to the one in the JSON feed",
                    "smoke.something == \"smoketest\"",
                    operation.getCondition().getSelection());
        }

        assertNull(reader.next());
    }

    @Test
    public void testFeedWithTestAndSetConditionOrderingOne() {
        testFeedWithTestAndSetCondition("""
                                        [
                                          {
                                            "put": "id:unittest:smoke::whee",
                                            "condition": "smoke.something == \\"smoketest\\"",
                                            "fields": {
                                              "something": "smoketest",
                                              "nalle": "bamse"
                                            }
                                          },
                                          {
                                            "update": "id:unittest:testarray::whee",
                                            "condition": "smoke.something == \\"smoketest\\"",
                                            "fields": {
                                              "actualarray": {
                                                "add": [
                                                  "person",
                                                  "another person"
                                                ]
                                              }
                                            }
                                          },
                                          {
                                            "remove": "id:unittest:smoke::whee",
                                            "condition": "smoke.something == \\"smoketest\\""
                                          }
                                        ]
                                        """);
    }

    @Test
    public void testFeedWithTestAndSetConditionOrderingTwo() {
        testFeedWithTestAndSetCondition("""
                                        [
                                          {
                                            "condition": "smoke.something == \\"smoketest\\"",
                                            "put": "id:unittest:smoke::whee",
                                            "fields": {
                                              "something": "smoketest",
                                              "nalle": "bamse"
                                            }
                                          },
                                          {
                                            "condition": "smoke.something == \\"smoketest\\"",
                                            "update": "id:unittest:testarray::whee",
                                            "fields": {
                                              "actualarray": {
                                                "add": [
                                                  "person",
                                                  "another person"
                                                ]
                                              }
                                            }
                                          },
                                          {
                                            "condition": "smoke.something == \\"smoketest\\"",
                                            "remove": "id:unittest:smoke::whee"
                                          }
                                        ]
                                        """);
    }

    @Test
    public void testFeedWithTestAndSetConditionOrderingThree() {
        testFeedWithTestAndSetCondition("""
                                        [
                                          {
                                            "put": "id:unittest:smoke::whee",
                                            "fields": {
                                              "something": "smoketest",
                                              "nalle": "bamse"
                                            },
                                            "condition": "smoke.something == \\"smoketest\\""
                                          },
                                          {
                                            "update": "id:unittest:testarray::whee",
                                            "fields": {
                                              "actualarray": {
                                                "add": [
                                                  "person",
                                                  "another person"
                                                ]
                                              }
                                            },
                                            "condition": "smoke.something == \\"smoketest\\""
                                          },
                                          {
                                            "remove": "id:unittest:smoke::whee",
                                            "condition": "smoke.something == \\"smoketest\\""
                                          }
                                        ]
                                        """);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidFieldAfterFieldsFieldShouldFailParse() {
        String jsonData = """
                          [
                            {
                              "put": "id:unittest:smoke::whee",
                              "fields": {
                                "something": "smoketest",
                                "nalle": "bamse"
                              },
                              "bjarne": "stroustrup"
                            }
                          ]""";

        new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidFieldBeforeFieldsFieldShouldFailParse() {
        String jsonData = """
                          [
                            {
                              "update": "id:unittest:testarray::whee",
                              "what is this": "nothing to see here",
                              "fields": {
                                "actualarray": {
                                  "add": [
                                    "person",
                                    "another person"
                                  ]
                                }
                              }
                            }
                          ]""";
        new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidFieldWithoutFieldsFieldShouldFailParse() {
        String jsonData = """
                          [
                            {
                              "remove": "id:unittest:smoke::whee",
                              "what is love": "baby, do not hurt me... much
                            }
                          ]"""; // "

        new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
    }

    @Test
    public void testMissingOperation() {
        try {
            String jsonData = """
                              [
                                {
                                  "fields": {
                                    "actualarray": {
                                      "add": [
                                        "person",
                                        "another person"
                                      ]
                                    }
                                  }
                                }
                              ]""";

            new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
            fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertEquals("Missing a document operation ('put', 'update' or 'remove')", e.getMessage());
        }
    }

    @Test
    public void testMissingFieldsMapInPut() {
        try {
            String jsonData = """
                              [
                                {
                                  "put": "id:unittest:smoke::whee"
                                }
                              ]""";

            new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
            fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertEquals("put of document id:unittest:smoke::whee is missing a 'fields' map", e.getMessage());
        }
    }

    @Test
    public void testMissingFieldsMapInUpdate() {
        try {
            String jsonData = """
                              [
                                {
                                  "update": "id:unittest:smoke::whee"
                                }
                              ]""";

            new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
            fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertEquals("Update of document id:unittest:smoke::whee is missing a 'fields' map", e.getMessage());
        }
    }

    @Test
    public void testNullValues() throws IOException {
        Document doc = docFromJson("""
                                   {
                                     "put": "id:unittest:testnull::doc1",
                                     "fields": {
                                       "intfield": null,
                                       "stringfield": null,
                                       "arrayfield": null,
                                       "weightedsetfield": null,
                                       "mapfield": null,
                                       "tensorfield": null
                                     }
                                   }
                                   """);
        assertFieldValueNull(doc, "intfield");
        assertFieldValueNull(doc, "stringfield");
        assertFieldValueNull(doc, "arrayfield");
        assertFieldValueNull(doc, "weightedsetfield");
        assertFieldValueNull(doc, "mapfield");
        assertFieldValueNull(doc, "tensorfield");
    }

    @Test(expected=JsonReaderException.class)
    public void testNullArrayElement() throws IOException {
        docFromJson("""
                     {
                       "put": "id:unittest:testnull::doc1",
                       "fields": {
                         "arrayfield": [ null ]
                       }
                     }
                     """);
        fail();
    }

    private void assertFieldValueNull(Document doc, String fieldName) {
        Field field = doc.getField(fieldName);
        assertNotNull(field);
        FieldValue fieldValue = doc.getFieldValue(field);
        assertNull(fieldValue);
    }


    static ByteArrayInputStream jsonToInputStream(String json) {
        return new ByteArrayInputStream(Utf8.toBytes(json));
    }

    @Test
    public void testParsingWithoutTensorField() {
        Document doc = createPutWithoutTensor().getDocument();
        assertEquals("testtensor", doc.getId().getDocType());
        assertEquals("id:unittest:testtensor::0", doc.getId().toString());
        TensorFieldValue fieldValue = (TensorFieldValue)doc.getFieldValue(doc.getField("sparse_tensor"));
        assertNull(fieldValue);
    }

    @Test
    public void testParsingOfEmptyTensor() {
        assertSparseTensorField("tensor(x{},y{}):{}", createPutWithSparseTensor("{}"));
    }

    @Test
    public void testParsingOfTensorWithEmptyCells() {
        assertSparseTensorField("tensor(x{},y{}):{}",
                                createPutWithSparseTensor(inputJson("{ 'cells': [] }")));
    }

    @Test
    public void testDisallowedDenseTensorShortFormWithoutValues() {
        assertCreatePutFails(inputJson("{ 'values': [] }"), "dense_tensor",
                "The 'values' array does not contain any values");
        assertCreatePutFails(inputJson("{ 'values': '' }"), "dense_tensor",
                "The 'values' string does not contain any values");
    }

    @Test
    public void testDisallowedMixedTensorShortFormWithoutValues() {
        assertCreatePutFails(inputJson("{\"blocks\":{ \"a\": [] } }"),
                "mixed_tensor", "Expected 3 values, but got 0");
        assertCreatePutFails(inputJson("{\"blocks\":[ {\"address\":{\"x\":\"a\"}, \"values\": [] } ] }"),
                "mixed_tensor", "Expected 3 values, but got 0");
    }

    @Test
    public void testParsingOfSparseTensorWithCells() {
        Tensor tensor = assertSparseTensorField("{{x:a,y:b}:2.0,{x:c,y:b}:3.0}",
                                createPutWithSparseTensor("""
                                                          {
                                                            "type": "tensor(x{},y{})",
                                                            "cells": [
                                                              { "address": { "x": "a", "y": "b" }, "value": 2.0 },
                                                              { "address": { "x": "c", "y": "b" }, "value": 3.0 }
                                                            ]
                                                          }
                                                          """));
        assertTrue(tensor instanceof MappedTensor); // any functional instance is fine
    }

    @Test
    public void testParsingOfDenseTensorWithCells() {
        Tensor tensor = assertTensorField("{{x:0,y:0}:2.0,{x:1,y:0}:3.0}",
                                          createPutWithTensor("""
                                                              {
                                                                "cells": [
                                                                  { "address": { "x": 0, "y": 0 }, "value": 2.0 },
                                                                  { "address": { "x": 1, "y": 0 }, "value": 3.0 }
                                                                ]
                                                              }
                                                              """,
                                                              "dense_unbound_tensor"),
                                          "dense_unbound_tensor");
        assertTrue(tensor instanceof IndexedTensor); // this matters for performance
    }

    @Test
    public void testParsingOfDenseTensorOnDenseForm() {
        Tensor.Builder builder = Tensor.Builder.of(TensorType.fromSpec("tensor(x[2],y[3])"));
        builder.cell().label("x", 0).label("y", 0).value(2.0);
        builder.cell().label("x", 0).label("y", 1).value(3.0);
        builder.cell().label("x", 0).label("y", 2).value(4.0);
        builder.cell().label("x", 1).label("y", 0).value(Double.POSITIVE_INFINITY);
        builder.cell().label("x", 1).label("y", 1).value(6.0);
        builder.cell().label("x", 1).label("y", 2).value(7.0);
        Tensor expected = builder.build();

        Tensor tensor = assertTensorField(expected,
                                          createPutWithTensor("""
                                                              {
                                                                "values": [2.0, 3.0, 4.0, "inf", 6.0, 7.0]
                                                              }""", "dense_tensor"), "dense_tensor");
        assertTrue(tensor instanceof IndexedTensor); // this matters for performance
    }

    @Test
    public void testParsingOfDenseTensorHexFormat() {
        Tensor.Builder builder = Tensor.Builder.of(TensorType.fromSpec("tensor<int8>(x[2],y[3])"));
        builder.cell().label("x", 0).label("y", 0).value(2.0);
        builder.cell().label("x", 0).label("y", 1).value(3.0);
        builder.cell().label("x", 0).label("y", 2).value(4.0);
        builder.cell().label("x", 1).label("y", 0).value(5.0);
        builder.cell().label("x", 1).label("y", 1).value(6.0);
        builder.cell().label("x", 1).label("y", 2).value(7.0);
        Tensor expected = builder.build();
        Tensor tensor = assertTensorField(expected,
                                          createPutWithTensor("""
                                                              {
                                                                "values": "020304050607"
                                                              }""", "dense_int8_tensor"), "dense_int8_tensor");
        assertTrue(tensor instanceof IndexedTensor); // this matters for performance
        tensor = assertTensorField(expected,
                                   createPutWithTensor("""
                                                       "020304050607"
                                                       """, "dense_int8_tensor"), "dense_int8_tensor");
        assertTrue(tensor instanceof IndexedTensor); // this matters for performance
        builder = Tensor.Builder.of(TensorType.fromSpec("tensor<float>(y[3])"));
        builder.cell().label("y", 0).value(42.0);
        builder.cell().label("y", 1).value(-0.125);
        builder.cell().label("y", 2).value(Double.POSITIVE_INFINITY);
        expected = builder.build();
        tensor = assertTensorField(expected,
                                   createPutWithTensor("""
                                                       "42280000be0000007f800000"
                                                       """, "dense_float_tensor"), "dense_float_tensor");
        try {
            assertTensorField(expected,
                              createPutWithTensor("""
                                                  ""
                                                  """, "dense_int8_tensor"), "dense_int8_tensor");
        }
        catch (IllegalArgumentException e) {
            assertTrue(Exceptions.toMessageString(e).contains(
                               "Bad string input for tensor with type tensor<int8>(x[2],y[3])"));
        }
    }

    @Test
    public void testParsingOfMixedTensorHexFormat() {
        Tensor.Builder builder = Tensor.Builder.of(TensorType.fromSpec("tensor<bfloat16>(x{},y[3])"));
        builder.cell().label("x", "foo").label("y", 0).value(2.0);
        builder.cell().label("x", "foo").label("y", 1).value(3.0);
        builder.cell().label("x", "foo").label("y", 2).value(4.0);
        builder.cell().label("x", "bar").label("y", 0).value(5.0);
        builder.cell().label("x", "bar").label("y", 1).value(6.0);
        builder.cell().label("x", "bar").label("y", 2).value(7.0);
        Tensor expected = builder.build();
        String mixedJson = """
                           {
                             "blocks":[
                               {"address":{"x":"foo"},"values":"400040404080"},
                               {"address":{"x":"bar"},"values":"40A040C040E0"}
                             ]
                           }
                           """;
        var put = createPutWithTensor(inputJson(mixedJson), "mixed_bfloat16_tensor");
        Tensor tensor = assertTensorField(expected, put, "mixed_bfloat16_tensor");
        mixedJson = """
                           {
                             "blocks":{"foo":"400040404080", "bar":"40A040C040E0"}
                           }
                    """;
        put = createPutWithTensor(inputJson(mixedJson), "mixed_bfloat16_tensor");
        tensor = assertTensorField(expected, put, "mixed_bfloat16_tensor");
    }

    /** Tests parsing of various tensor values set at the root, i.e. no 'cells', 'blocks' or 'values' */
    @Test
    public void testDirectValue() {
        assertTensorField("tensor(x{}):{a:2, b:3}", "sparse_single_dimension_tensor", "{'a':2.0, 'b':3.0}");
        assertTensorField("tensor(x[2],y[3]):[2, 3, 4, 5, 6, 7]]", "dense_tensor", "[2, 3, 4, 5, 6, 7]");
        assertTensorField("tensor(x[2],y[3]):[2, 3, 4, 5, 6, 7]]", "dense_tensor", "[[2, 3, 4], [5, 6, 7]]");
        assertTensorField("tensor(x{},y[3]):{a:[2, 3, 4], b:[4, 5, 6]}", "mixed_tensor", "{'a':[2, 3, 4], 'b':[4, 5, 6]}");
        assertTensorField("tensor(x{},y{}):{{x:a,y:0}:2, {x:b,y:1}:3}", "sparse_tensor",
                          "[{'address':{'x':'a','y':'0'},'value':2}, {'address':{'x':'b','y':'1'},'value':3}]");
    }

    @Test
    public void testDirectValueReservedNameKeys() {
        // Single-valued
        assertTensorField("tensor(x{}):{cells:2, b:3}", "sparse_single_dimension_tensor", "{'cells':2.0, 'b':3.0}");
        assertTensorField("tensor(x{}):{values:2, b:3}", "sparse_single_dimension_tensor", "{'values':2.0, 'b':3.0}");
        assertTensorField("tensor(x{}):{block:2, b:3}", "sparse_single_dimension_tensor", "{'block':2.0, 'b':3.0}");

        // Multi-valued
        assertTensorField("tensor(x{},y[3]):{cells:[2, 3, 4], b:[4, 5, 6]}", "mixed_tensor",
                          "{'cells':[2, 3, 4], 'b':[4, 5, 6]}");
        assertTensorField("tensor(x{},y[3]):{values:[2, 3, 4], b:[4, 5, 6]}", "mixed_tensor",
                          "{'values':[2, 3, 4], 'b':[4, 5, 6]}");
        assertTensorField("tensor(x{},y[3]):{block:[2, 3, 4], b:[4, 5, 6]}", "mixed_tensor",
                          "{'block':[2, 3, 4], 'b':[4, 5, 6]}");
    }

    @Test
    public void testParsingOfMixedTensorOnMixedForm() {
        Tensor.Builder builder = Tensor.Builder.of(TensorType.fromSpec("tensor(x{},y[3])"));
        builder.cell().label("x", 0).label("y", 0).value(2.0);
        builder.cell().label("x", 0).label("y", 1).value(3.0);
        builder.cell().label("x", 0).label("y", 2).value(4.0);
        builder.cell().label("x", 1).label("y", 0).value(5.0);
        builder.cell().label("x", 1).label("y", 1).value(6.0);
        builder.cell().label("x", 1).label("y", 2).value(7.0);
        Tensor expected = builder.build();

        String mixedJson =
                """
                {
                  "blocks":[
                    {"address":{"x":"0"},"values":[2.0,3.0,4.0]},
                    {"address":{"x":"1"},"values":[5.0,6.0,7.0]}
                  ]
                }
                """;
        Tensor tensor = assertTensorField(expected,
                                          createPutWithTensor(inputJson(mixedJson), "mixed_tensor"), "mixed_tensor");
        assertTrue(tensor instanceof MixedTensor); // this matters for performance

        String mixedJsonDirect =
                """
                [
                  {"address":{"x":"0","y":"0"},"value":2.0},
                  {"address":{"x":"0","y":"1"},"value":3.0},
                  {"address":{"x":"0","y":"2"},"value":4.0},
                  {"address":{"x":"1","y":"0"},"value":5.0},
                  {"address":{"x":"1","y":"1"},"value":6.0},
                  {"address":{"x":"1","y":"2"},"value":7.0}
                ]
                """;
        Tensor tensorDirect = assertTensorField(expected,
                                                createPutWithTensor(inputJson(mixedJsonDirect), "mixed_tensor"), "mixed_tensor");
        assertTrue(tensorDirect instanceof MixedTensor); // this matters for performance
    }

    @Test
    public void testMixedTensorInMixedFormWithSingleSparseDimensionShortForm() {
        Tensor.Builder builder = Tensor.Builder.of(TensorType.fromSpec("tensor(x{},y[3])"));
        builder.cell().label("x", 0).label("y", 0).value(2.0);
        builder.cell().label("x", 0).label("y", 1).value(3.0);
        builder.cell().label("x", 0).label("y", 2).value(4.0);
        builder.cell().label("x", 1).label("y", 0).value(5.0);
        builder.cell().label("x", 1).label("y", 1).value(6.0);
        builder.cell().label("x", 1).label("y", 2).value(7.0);
        Tensor expected = builder.build();

        String mixedJson = """
                           {
                             "blocks":{
                               "0":[2.0,3.0,4.0],
                               "1":[5.0,6.0,7.0]
                             }
                           }
                           """;
        Tensor tensor = assertTensorField(expected,
                                          createPutWithTensor(inputJson(mixedJson), "mixed_tensor"), "mixed_tensor");
        assertTrue(tensor instanceof MixedTensor); // this matters for performance
    }

    @Test
    public void testParsingOfTensorWithSingleCellInDifferentJsonOrder() {
        assertSparseTensorField("{{x:a,y:b}:2.0}",
                                createPutWithSparseTensor("""
                                                          {
                                                            "cells": [
                                                              { "value": 2.0,
                                                                "address": { "x": "a", "y": "b" } }
                                                            ]
                                                          }
                                                          """));
    }

    @Test
    public void testAssignUpdateOfEmptySparseTensor() {
        assertTensorAssignUpdateSparseField("tensor(x{},y{}):{}", createAssignUpdateWithSparseTensor("{}"));
    }

    @Test
    public void testAssignUpdateOfEmptyDenseTensor() {
        try {
            assertTensorAssignUpdateSparseField("tensor(x{},y{}):{}", createAssignUpdateWithTensor("{}", "dense_unbound_tensor"));
        }
        catch (IllegalArgumentException e) {
            assertEquals("An indexed tensor must have a value",
                         "Error in 'dense_unbound_tensor': Tensor of type tensor(x[],y[]) has no values",
                         Exceptions.toMessageString(e));
        }
    }

    @Test
    public void testAssignUpdateOfNullTensor() {
        ClearValueUpdate clearUpdate = (ClearValueUpdate) getTensorField(createAssignUpdateWithSparseTensor(null)).getValueUpdate(0);
        assertNotNull(clearUpdate);
        assertNull(clearUpdate.getValue());
    }

    @Test
    public void testAssignUpdateOfTensorWithCells() {
        assertTensorAssignUpdateSparseField("{{x:a,y:b}:2.0,{x:c,y:b}:3.0}",
                                            createAssignUpdateWithSparseTensor("""
                                                                               {
                                                                                "cells": [
                                                                                  { "address": { "x": "a", "y": "b" },
                                                                                    "value": 2.0 },
                                                                                  { "address": { "x": "c", "y": "b" },
                                                                                    "value": 3.0 }
                                                                                ]
                                                                              }
                                                                              """));
    }

    @Test
    public void testAssignUpdateOfTensorDenseShortForm() {
        assertTensorAssignUpdateDenseField("tensor(x[2],y[3]):[[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]]",
                                           createAssignUpdateWithTensor("""
                                                                        {
                                                                          "values": [1,2,3,4,5,6]
                                                                        }
                                                                        """,
                                                                        "dense_tensor"));
    }

    @Test
    public void tensor_modify_update_with_replace_operation() {
        assertTensorModifyUpdate("{{x:a,y:b}:2.0}", TensorModifyUpdate.Operation.REPLACE, "sparse_tensor",
                                 """
                                 {
                                   "operation": "replace",
                                   "cells": [
                                     { "address": { "x": "a", "y": "b" }, "value": 2.0 }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_modify_update_with_add_operation() {
        assertTensorModifyUpdate("{{x:a,y:b}:2.0}", TensorModifyUpdate.Operation.ADD, "sparse_tensor",
                                 """
                                 {
                                   "operation": "add",
                                   "cells": [
                                     { "address": { "x": "a", "y": "b" }, "value": 2.0 }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_modify_update_with_multiply_operation() {
        assertTensorModifyUpdate("{{x:a,y:b}:2.0}", TensorModifyUpdate.Operation.MULTIPLY, "sparse_tensor",
                                 """
                                 {
                                   "operation": "multiply",
                                   "cells": [
                                     { "address": { "x": "a", "y": "b" }, "value": 2.0 }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_modify_update_with_create_non_existing_cells_true() {
        assertTensorModifyUpdate("{{x:a,y:b}:2.0}", TensorModifyUpdate.Operation.ADD, true, "sparse_tensor",
                                 """
                                 {
                                   "operation": "add",
                                   "create": true,
                                   "cells": [
                                     { "address": { "x": "a", "y": "b" }, "value": 2.0 }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_modify_update_with_create_non_existing_cells_false() {
        assertTensorModifyUpdate("{{x:a,y:b}:2.0}", TensorModifyUpdate.Operation.ADD, false, "sparse_tensor",
                                 """
                                 {
                                   "operation": "add",
                                   "create": false,
                                   "cells": [
                                     { "address": { "x": "a", "y": "b" }, "value": 2.0 }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_modify_update_treats_the_input_tensor_as_sparse() {
        // Note that the type of the tensor in the modify update is sparse (it only has mapped dimensions).
        assertTensorModifyUpdate("tensor(x{},y{}):{{x:0,y:0}:2.0, {x:1,y:2}:3.0}",
                                 TensorModifyUpdate.Operation.REPLACE, "dense_tensor",
                                 """
                                 {
                                   "operation": "replace",
                                   "cells": [
                                     { "address": { "x": "0", "y": "0" }, "value": 2.0 },
                                     { "address": { "x": "1", "y": "2" }, "value": 3.0 }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_modify_update_on_non_tensor_field_throws() {
        try {
            JsonReader reader = createReader("""
                                             {
                                               "update": "id:unittest:smoke::doc1",
                                               "fields": {
                                                 "something": {
                                                   "modify": {}
                                                 }
                                               }
                                             }
                                             """);
            reader.readSingleDocument(DocumentOperationType.UPDATE, "id:unittest:smoke::doc1");
            fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertEquals("Error in 'something': A modify update can only be applied to tensor fields. Field 'something' is of type 'string'",
                         Exceptions.toMessageString(e));
        }
    }

    @Test
    public void tensor_modify_update_on_dense_unbound_tensor_throws() {
        illegalTensorModifyUpdate("Error in 'dense_unbound_tensor': A modify update cannot be applied to tensor types with indexed unbound dimensions. Field 'dense_unbound_tensor' has unsupported tensor type 'tensor(x[],y[])'",
                                  "dense_unbound_tensor",
                                  """
                                  {
                                    "operation": "replace",
                                    "cells": [
                                      { "address": { "x": "0", "y": "0" }, "value": 2.0 }
                                    ]
                                  }""");
    }

    @Test
    public void tensor_modify_update_on_sparse_tensor_with_single_dimension_short_form() {
        assertTensorModifyUpdate("{{x:a}:2.0, {x:c}: 3.0}", TensorModifyUpdate.Operation.REPLACE, "sparse_single_dimension_tensor",
                                 """
                                 {
                                   "operation": "replace",
                                   "cells": {
                                     "a": 2.0,
                                     "c": 3.0
                                   }
                                 }""");
    }

    @Test
    public void tensor_modify_update_with_replace_operation_mixed() {
        assertTensorModifyUpdate("{{x:a,y:0}:2.0}", TensorModifyUpdate.Operation.REPLACE, "mixed_tensor",
                                 """
                                 {
                                   "operation": "replace",
                                   "cells": [
                                     { "address": { "x": "a", "y": "0" }, "value": 2.0 }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_modify_update_with_replace_operation_mixed_block_short_form_array() {
        assertTensorModifyUpdate("{{x:a,y:0}:1,{x:a,y:1}:2,{x:a,y:2}:3}", TensorModifyUpdate.Operation.REPLACE, "mixed_tensor",
                                 """
                                 {
                                   "operation": "replace",
                                   "blocks": [
                                     { "address": { "x": "a" }, "values": [1,2,3] }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_modify_update_with_replace_operation_mixed_block_short_form_must_specify_full_subspace() {
        illegalTensorModifyUpdate("Error in 'mixed_tensor': At {x:a}: Expected 3 values, but got 2",
                                  "mixed_tensor",
                                  """
                                  {
                                    "operation": "replace",
                                    "blocks": {
                                      "a": [2,3]
                                    }
                                  }""");
    }

    @Test
    public void tensor_modify_update_with_replace_operation_mixed_block_short_form_map() {
        assertTensorModifyUpdate("{{x:a,y:0}:1,{x:a,y:1}:2,{x:a,y:2}:3}", TensorModifyUpdate.Operation.REPLACE, "mixed_tensor",
                                 """
                                 {
                                   "operation": "replace",
                                   "blocks": {
                                     "a": [1,2,3]
                                   }
                                 }""");
    }

    @Test
    public void tensor_modify_update_with_add_operation_mixed() {
        assertTensorModifyUpdate("{{x:a,y:0}:2.0}", TensorModifyUpdate.Operation.ADD, "mixed_tensor",
                                 """
                                 {
                                   "operation": "add",
                                   "cells": [
                                     { "address": { "x": "a", "y": "0" }, "value": 2.0 }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_modify_update_with_multiply_operation_mixed() {
        assertTensorModifyUpdate("{{x:a,y:0}:2.0}", TensorModifyUpdate.Operation.MULTIPLY, "mixed_tensor",
                                 """
                                 {
                                   "operation": "multiply",
                                   "cells": [
                                     { "address": { "x": "a", "y": "0" }, "value": 2.0 }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_modify_update_with_out_of_bound_cells_throws() {
        illegalTensorModifyUpdate("Error in 'dense_tensor': Dimension 'y' has label '3' but type is tensor(x[2],y[3])",
                                  "dense_tensor",
                                  """
                                  {
                                    "operation": "replace",
                                    "cells": [
                                      { "address": { "x": "0", "y": "3" }, "value": 2.0 }
                                    ]
                                  }""");
    }

    @Test
    public void tensor_modify_update_with_out_of_bound_cells_throws_mixed() {
        illegalTensorModifyUpdate("Error in 'mixed_tensor': Dimension 'y' has label '3' but type is tensor(x{},y[3])",
                                  "mixed_tensor",
                                  """
                                  {
                                    "operation": "replace",
                                    "cells": [
                                      { "address": { "x": "0", "y": "3" }, "value": 2.0 }
                                    ]
                                  }""");
    }


    @Test
    public void tensor_modify_update_with_unknown_operation_throws() {
        illegalTensorModifyUpdate("Error in 'sparse_tensor': Unknown operation 'unknown' in modify update for field 'sparse_tensor'",
                                  "sparse_tensor",
                                  """
                                  {
                                    "operation": "unknown",
                                    "cells": [
                                      { "address": { "x": "a", "y": "b" }, "value": 2.0 }
                                    ]
                                  }""");
    }

    @Test
    public void tensor_modify_update_without_operation_throws() {
        illegalTensorModifyUpdate("Error in 'sparse_tensor': Modify update for field 'sparse_tensor' does not contain an operation",
                                  "sparse_tensor",
                                  """
                                  {
                                    "cells": []
                                  }""");
    }

    @Test
    public void tensor_modify_update_without_cells_throws() {
        illegalTensorModifyUpdate("Error in 'sparse_tensor': Modify update for field 'sparse_tensor' does not contain tensor cells",
                                  "sparse_tensor",
                                  """
                                  {
                                    "operation": "replace"
                                  }""");
    }

    @Test
    public void tensor_modify_update_with_unknown_content_throws() {
        illegalTensorModifyUpdate("Error in 'sparse_tensor': Unknown JSON string 'unknown' in modify update for field 'sparse_tensor'",
                                  "sparse_tensor",
                                  """
                                  {
                                    "unknown": "here"
                                  }""");
    }

    @Test
    public void tensor_add_update_on_sparse_tensor() {
        assertTensorAddUpdate("{{x:a,y:b}:2.0, {x:c,y:d}: 3.0}", "sparse_tensor",
                              """
                              {
                                "cells": [
                                  { "address": { "x": "a", "y": "b" }, "value": 2.0 },
                                  { "address": { "x": "c", "y": "d" }, "value": 3.0 }
                                ]
                              }""");
    }

    @Test
    public void tensor_add_update_on_sparse_tensor_with_single_dimension_short_form() {
        assertTensorAddUpdate("{{x:a}:2.0, {x:c}: 3.0}", "sparse_single_dimension_tensor",
                              """
                              {
                                "cells": {
                                  "a": 2.0,
                                  "c": 3.0
                                }
                              }""");
    }

    @Test
    public void tensor_add_update_on_mixed_tensor() {
        assertTensorAddUpdate("{{x:a,y:0}:2.0, {x:a,y:1}:3.0, {x:a,y:2}:0.0}", "mixed_tensor",
                              """
                              {
                                "cells": [
                                  { "address": { "x": "a", "y": "0" }, "value": 2.0 },
                                  { "address": { "x": "a", "y": "1" }, "value": 3.0 }
                                ]
                              }""");
    }

    @Test
    public void tensor_add_update_on_mixed_with_out_of_bound_dense_cells_throws() {
        illegalTensorAddUpdate("Error in 'mixed_tensor': Index 3 out of bounds for length 3",
                               "mixed_tensor",
                               """
                               {
                                 "cells": [
                                   { "address": { "x": "0", "y": "3" }, "value": 2.0 }
                                 ]
                               }""");
    }

    @Test
    public void tensor_add_update_on_dense_tensor_throws() {
        illegalTensorAddUpdate("Error in 'dense_tensor': An add update can only be applied to tensors with at least one sparse dimension. Field 'dense_tensor' has unsupported tensor type 'tensor(x[2],y[3])'",
                               "dense_tensor",
                               """
                               {
                                 "cells": [ ]
                               }""");
    }

    @Test
    public void tensor_add_update_on_not_fully_specified_cell_throws() {
        illegalTensorAddUpdate("Error in 'sparse_tensor': Missing a label for dimension 'y' for tensor(x{},y{})",
                               "sparse_tensor",
                               """
                               {
                                 "cells": [
                                   { "address": { "x": "a" }, "value": 2.0 }
                                 ]
                               }""");
    }

    @Test
    public void tensor_add_update_without_cells_throws() {
        illegalTensorAddUpdate("Error in 'sparse_tensor': Add update for field 'sparse_tensor' does not contain tensor cells",
                               "sparse_tensor",
                               "{}");
        illegalTensorAddUpdate("Error in 'mixed_tensor': Add update for field 'mixed_tensor' does not contain tensor cells",
                               "mixed_tensor",
                               "{}");
    }

    @Test
    public void tensor_remove_update_on_sparse_tensor() {
        assertTensorRemoveUpdate("{{x:a,y:b}:1.0,{x:c,y:d}:1.0}", "sparse_tensor",
                                 """
                                 {
                                   "addresses": [
                                     { "x": "a", "y": "b" },
                                     { "x": "c", "y": "d" }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_remove_update_on_mixed_tensor() {
        assertTensorRemoveUpdate("{{x:1}:1.0,{x:2}:1.0}", "mixed_tensor",
                                 """
                                 {
                                   "addresses": [
                                     { "x": "1" },
                                     { "x": "2" }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_remove_update_on_sparse_tensor_with_not_fully_specified_address() {
        assertTensorRemoveUpdate("{{y:b}:1.0,{y:d}:1.0}", "sparse_tensor",
                                 """
                                 {
                                   "addresses": [
                                     { "y": "b" },
                                     { "y": "d" }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_remove_update_on_mixed_tensor_with_not_fully_specified_address() {
        assertTensorRemoveUpdate("{{x:1,z:a}:1.0,{x:2,z:b}:1.0}", "mixed_tensor_adv",
                                 """
                                 {
                                   "addresses": [
                                     { "x": "1", "z": "a" },
                                     { "x": "2", "z": "b" }
                                   ]
                                 }""");
    }

    @Test
    public void tensor_remove_update_on_mixed_tensor_with_dense_addresses_throws() {
        illegalTensorRemoveUpdate("Error in 'mixed_tensor': Indexed dimension address 'y' should not be specified in remove update",
                                  "mixed_tensor",
                                  """
                                  {
                                    "addresses": [
                                      { "x": "1", "y": "0" },
                                      { "x": "2", "y": "0" }
                                    ]
                                  }""");
    }

    @Test
    public void tensor_remove_update_on_dense_tensor_throws() {
        illegalTensorRemoveUpdate("Error in 'dense_tensor': A remove update can only be applied to tensors with at least one sparse dimension. Field 'dense_tensor' has unsupported tensor type 'tensor(x[2],y[3])'",
                                  "dense_tensor",
                                  """
                                  {
                                    "addresses": []
                                  }""");
    }

    @Test
    public void tensor_remove_update_with_stray_dimension_throws() {
        illegalTensorRemoveUpdate("Error in 'sparse_tensor': tensor(x{},y{}) does not contain dimension 'foo'",
                                  "sparse_tensor",
                                  """
                                  {
                                    "addresses": [
                                      { "x": "a", "foo": "b" }
                                    ]
                                  }""");

        illegalTensorRemoveUpdate("Error in 'sparse_tensor': tensor(x{}) does not contain dimension 'foo'",
                                  "sparse_tensor",
                                  """
                                  {
                                    "addresses": [
                                      { "x": "c" },
                                      { "x": "a", "foo": "b" }
                                    ]
                                  }""");
    }

    @Test
    public void tensor_remove_update_without_cells_throws() {
        illegalTensorRemoveUpdate("Error in 'sparse_tensor': Remove update for field 'sparse_tensor' does not contain tensor addresses",
                                  "sparse_tensor",
                                  """
                                  {
                                    "addresses": []
                                  }""");

        illegalTensorRemoveUpdate("Error in 'mixed_tensor': Remove update for field 'mixed_tensor' does not contain tensor addresses",
                                  "mixed_tensor",
                                  """
                                  {
                                    "addresses": []
                                  }""");
    }

    @Test
    public void require_that_parser_propagates_datatype_parser_errors_predicate() {
        assertParserErrorMatches(
                "In document 'id:unittest:testpredicate::0': Could not parse field 'boolean' of type predicate: " +
                "line 1:10 no viable alternative at character '>'",
                """
                [
                  {
                    "fields": {
                      "boolean": "timestamp > 9000"
                    },
                    "put": "id:unittest:testpredicate::0"
                  }
                ]
                """);
    }

    @Test
    public void require_that_parser_propagates_datatype_parser_errors_string_as_int() {
        assertParserErrorMatches(
                "In document 'id:unittest:testint::0': Could not parse field 'integerfield' of type int: " +
                "For input string: \" 1\"",
                """
                [
                  {
                    "fields": {
                      "integerfield": " 1"
                    },
                    "put": "id:unittest:testint::0"
                  }
                ]
                """);
    }

    @Test
    public void require_that_parser_propagates_datatype_parser_errors_overflowing_int() {
        assertParserErrorMatches(
                "In document 'id:unittest:testint::0': Could not parse field 'integerfield' of type int: " +
                "For input string: \"281474976710656\"",
                """
                [
                  {
                    "fields": {
                      "integerfield": 281474976710656
                    },
                    "put": "id:unittest:testint::0"
                  }
                ]
                """);
    }

    @Test
    public void requireThatUnknownDocTypeThrowsIllegalArgumentException() {
        String jsonData = """
                          [
                            {
                              "put": "id:ns:walrus::walrus1",
                              "fields": {
                                "aField": 42
                              }
                            }
                          ]
                          """;
        try {
            new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("Document type walrus does not exist", e.getMessage());
        }
    }

    private static final String TENSOR_DOC_ID = "id:unittest:testtensor::0";

    private void illegalTensorModifyUpdate(String expectedMessage, String field, String ... jsonLines) {
        try {
            createTensorModifyUpdate(inputJson(jsonLines), field);
            fail("Expected exception");
        }
        catch (IllegalArgumentException expected) {
            assertEquals(expectedMessage, Exceptions.toMessageString(expected));
        }
    }

    private void illegalTensorAddUpdate(String expectedMessage, String field, String ... jsonLines) {
        try {
            createTensorAddUpdate(inputJson(jsonLines), field);
            fail("Expected exception");
        }
        catch (IllegalArgumentException expected) {
            assertEquals(expectedMessage, Exceptions.toMessageString(expected));
        }
    }

    private void illegalTensorRemoveUpdate(String expectedMessage, String field, String ... jsonLines) {
        try {
            createTensorRemoveUpdate(inputJson(jsonLines), field);
            fail("Expected exception");
        }
        catch (IllegalArgumentException expected) {
            assertEquals(expectedMessage, Exceptions.toMessageString(expected));
        }
    }

    private DocumentPut createPutWithoutTensor() {
        JsonReader reader = createReader(inputJson("[ { 'put': '" + TENSOR_DOC_ID + "', 'fields': { } } ]"));
        return (DocumentPut) reader.next();
    }

    private DocumentPut createPutWithSparseTensor(String inputTensor) {
        return createPutWithTensor(inputTensor, "sparse_tensor");
    }
    private DocumentPut createPutWithTensor(String inputTensor, String tensorFieldName) {
        JsonReader streaming = createReader("""
                                         {
                                           "fields": {
                                             "%s": %s
                                           }
                                         }
                                         """.formatted(tensorFieldName, inputTensor));
        DocumentPut lazyParsed = (DocumentPut) streaming.readSingleDocumentStreaming(DocumentOperationType.PUT, TENSOR_DOC_ID).operation();
        JsonReader reader = createReader("""
                                         [
                                           {
                                             "put": "%s",
                                             "fields": {
                                               "%s": %s
                                             }
                                           }
                                         ]""".formatted(TENSOR_DOC_ID, tensorFieldName, inputTensor));
        DocumentPut bufferParsed = (DocumentPut) reader.next();
        assertEquals(lazyParsed, bufferParsed);
        return bufferParsed;
    }

    private DocumentUpdate createAssignUpdateWithSparseTensor(String inputTensor) {
        return createAssignUpdateWithTensor(inputTensor, "sparse_tensor");
    }
    private DocumentUpdate createAssignUpdateWithTensor(String inputTensor, String tensorFieldName) {
        return createTensorUpdate("assign", inputTensor, tensorFieldName);
    }

    private static Tensor assertSparseTensorField(String expectedTensor, DocumentPut put) {
        return assertTensorField(expectedTensor, put, "sparse_tensor");
    }
    private Tensor assertTensorField(String expectedTensor, String fieldName, String inputJson) {
        return assertTensorField(expectedTensor, createPutWithTensor(inputJson(inputJson), fieldName), fieldName);
    }
    private static Tensor assertTensorField(String expectedTensor, DocumentPut put, String tensorFieldName) {
        return assertTensorField(Tensor.from(expectedTensor), put, tensorFieldName);
    }
    private static Tensor assertTensorField(Tensor expectedTensor, DocumentPut put, String tensorFieldName) {
        Document doc = put.getDocument();
        assertEquals("testtensor", doc.getId().getDocType());
        assertEquals(TENSOR_DOC_ID, doc.getId().toString());
        TensorFieldValue fieldValue = (TensorFieldValue)doc.getFieldValue(doc.getField(tensorFieldName));
        assertEquals(expectedTensor, fieldValue.getTensor().get());
        return fieldValue.getTensor().get();
    }

    private static void assertTensorFieldUpdate(DocumentUpdate update, String tensorFieldName) {
        assertEquals("testtensor", update.getId().getDocType());
        assertEquals(TENSOR_DOC_ID, update.getId().toString());
        assertEquals(1, update.fieldUpdates().size());
        assertEquals(1, update.getFieldUpdate(tensorFieldName).size());
    }

    private static void assertTensorAssignUpdateSparseField(String expectedTensor, DocumentUpdate update) {
        assertEquals("testtensor", update.getId().getDocType());
        assertEquals(TENSOR_DOC_ID, update.getId().toString());
        AssignValueUpdate assignUpdate = (AssignValueUpdate) getTensorField(update, "sparse_tensor").getValueUpdate(0);
        TensorFieldValue fieldValue = (TensorFieldValue) assignUpdate.getValue();
        assertEquals(Tensor.from(expectedTensor), fieldValue.getTensor().get());
    }

    private static void assertTensorAssignUpdateDenseField(String expectedTensor, DocumentUpdate update) {
        assertEquals("testtensor", update.getId().getDocType());
        assertEquals(TENSOR_DOC_ID, update.getId().toString());
        AssignValueUpdate assignUpdate = (AssignValueUpdate) getTensorField(update, "dense_tensor").getValueUpdate(0);
        TensorFieldValue fieldValue = (TensorFieldValue) assignUpdate.getValue();
        assertEquals(Tensor.from(expectedTensor), fieldValue.getTensor().get());
    }

    private void assertTensorModifyUpdate(String expectedTensor, TensorModifyUpdate.Operation expectedOperation,
                                          String tensorFieldName, String modifyJson) {
        assertTensorModifyUpdate(expectedTensor, expectedOperation, false, tensorFieldName,
                createTensorModifyUpdate(modifyJson, tensorFieldName));
    }

    private void assertTensorModifyUpdate(String expectedTensor, TensorModifyUpdate.Operation expectedOperation,
                                          boolean expectedCreateNonExistingCells,
                                          String tensorFieldName, String modifyJson) {
        assertTensorModifyUpdate(expectedTensor, expectedOperation, expectedCreateNonExistingCells, tensorFieldName,
                createTensorModifyUpdate(modifyJson, tensorFieldName));
    }

    private static void assertTensorModifyUpdate(String expectedTensor, TensorModifyUpdate.Operation expectedOperation,
                                                 boolean expectedCreateNonExistingCells,
                                                 String tensorFieldName, DocumentUpdate update) {
        assertTensorFieldUpdate(update, tensorFieldName);
        TensorModifyUpdate modifyUpdate = (TensorModifyUpdate) update.getFieldUpdate(tensorFieldName).getValueUpdate(0);
        assertEquals(expectedOperation, modifyUpdate.getOperation());
        assertEquals(Tensor.from(expectedTensor), modifyUpdate.getValue().getTensor().get());
        assertEquals(expectedCreateNonExistingCells, modifyUpdate.getCreateNonExistingCells());
    }

    private DocumentUpdate createTensorModifyUpdate(String modifyJson, String tensorFieldName) {
        return createTensorUpdate("modify", modifyJson, tensorFieldName);
    }

    private DocumentUpdate createTensorAddUpdate(String tensorJson, String tensorFieldName) {
        return createTensorUpdate("add", tensorJson, tensorFieldName);
    }

    private DocumentUpdate createTensorRemoveUpdate(String tensorJson, String tensorFieldName) {
        return createTensorUpdate("remove", tensorJson, tensorFieldName);
    }

    private DocumentUpdate createTensorUpdate(String operation, String tensorJson, String tensorFieldName) {
        JsonReader streaming = createReader("""
                                            {
                                              "fields": {
                                                "%s": {
                                                   "%s": %s
                                                }
                                              }
                                            }""".formatted(tensorFieldName, operation, tensorJson));
        DocumentUpdate lazyParsed = (DocumentUpdate) streaming.readSingleDocumentStreaming(DocumentOperationType.UPDATE, TENSOR_DOC_ID).operation();
        JsonReader reader = createReader("""
                                         [
                                           {
                                             "update": "%s",
                                             "fields": {
                                               "%s": {
                                                 "%s": %s
                                               }
                                             }
                                           }
                                         ]""".formatted(TENSOR_DOC_ID, tensorFieldName, operation, tensorJson));
        DocumentUpdate bufferParsed = (DocumentUpdate) reader.next();
        assertEquals(lazyParsed, bufferParsed);
        return bufferParsed;
    }

    private void assertTensorAddUpdate(String expectedTensor, String tensorFieldName, String tensorJson) {
        assertTensorAddUpdate(expectedTensor, tensorFieldName,
                createTensorAddUpdate(tensorJson, tensorFieldName));
    }

    private static void assertTensorAddUpdate(String expectedTensor, String tensorFieldName, DocumentUpdate update) {
        assertTensorFieldUpdate(update, tensorFieldName);
        TensorAddUpdate addUpdate = (TensorAddUpdate) update.getFieldUpdate(tensorFieldName).getValueUpdate(0);
        assertEquals(Tensor.from(expectedTensor), addUpdate.getValue().getTensor().get());
    }

    private void assertTensorRemoveUpdate(String expectedTensor, String tensorFieldName, String tensorJson) {
        assertTensorRemoveUpdate(expectedTensor, tensorFieldName, createTensorRemoveUpdate(tensorJson, tensorFieldName));
    }

    private static void assertTensorRemoveUpdate(String expectedTensor, String tensorFieldName, DocumentUpdate update) {
        assertTensorFieldUpdate(update, tensorFieldName);
        TensorRemoveUpdate removeUpdate = (TensorRemoveUpdate) update.getFieldUpdate(tensorFieldName).getValueUpdate(0);
        assertEquals(Tensor.from(expectedTensor), removeUpdate.getValue().getTensor().get());
    }

    private static FieldUpdate getTensorField(DocumentUpdate update) {
        return getTensorField(update, "sparse_tensor");
    }

    private static FieldUpdate getTensorField(DocumentUpdate update, String fieldName) {
        FieldUpdate fieldUpdate = update.getFieldUpdate(fieldName);
        assertEquals(1, fieldUpdate.size());
        return fieldUpdate;
    }

    // NOTE: Do not call this method multiple times from a test method as it's using the ExpectedException rule
    private void assertParserErrorMatches(String expectedError, String... json) {
        String jsonData = inputJson(json);
        try {
            new JsonReader(types, jsonToInputStream(jsonData), parserFactory).next();
            fail();
        } catch (JsonReaderException e) {
            assertEquals(expectedError, Exceptions.toMessageString(e));
        }
    }

    private void assertCreatePutFails(String tensor, String name, String msg) {
        try {
            createPutWithTensor(inputJson(tensor), name);
            fail("Expected exception");
        } catch (IllegalArgumentException e) {
            assertTrue(Exceptions.toMessageString(e).contains(msg));
        }
    }

}
