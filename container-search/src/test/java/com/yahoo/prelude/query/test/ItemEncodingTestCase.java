// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.prelude.query.test;

import com.yahoo.prelude.query.AndItem;
import com.yahoo.prelude.query.AndSegmentItem;
import com.yahoo.prelude.query.EquivItem;
import com.yahoo.prelude.query.MarkerWordItem;
import com.yahoo.prelude.query.NearItem;
import com.yahoo.prelude.query.NumericInItem;
import com.yahoo.prelude.query.ONearItem;
import com.yahoo.prelude.query.PureWeightedInteger;
import com.yahoo.prelude.query.PureWeightedString;
import com.yahoo.prelude.query.StringInItem;
import com.yahoo.prelude.query.WeakAndItem;
import com.yahoo.prelude.query.WordItem;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item encoding tests
 *
 * @author bratseth
 */
public class ItemEncodingTestCase {

    private void assertType(ByteBuffer buffer, int etype, int efeatures) {
        byte CODE_MASK = 0b00011111;
        byte features_and_type = buffer.get();
        int features = (features_and_type & 0xe0) >> 5;
        int type = features_and_type & CODE_MASK;
        if (type == CODE_MASK) {
            byte type_extension = buffer.get();
            assertTrue(type_extension >= 0);
            type += type_extension;
        }
        assertEquals(etype, type, "Code");
        assertEquals(efeatures, features, "Features");
    }

    private void assertWeight(ByteBuffer buffer, int weight) {
        int w = (weight > (1 << 5)) ? buffer.getShort() & 0x3fff: buffer.get();
        assertEquals(weight, w, "Weight");
    }

    @Test
    void testWordItemEncoding() {
        WordItem word = new WordItem("test");

        word.setWeight(150);
        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = word.encode(buffer);

        buffer.flip();

        assertEquals(1, count, "Serialization count");

        assertType(buffer, 4, 1);
        assertWeight(buffer, 150);

        assertEquals(0, buffer.get(), "Index length");
        assertEquals(4, buffer.get(), "Word length");
        assertEquals(4, buffer.remaining(), "Word length");
        assertEquals('t', buffer.get());
        assertEquals('e', buffer.get());
        assertEquals('s', buffer.get());
        assertEquals('t', buffer.get());
    }

    @Test
    void testStartHostMarkerEncoding() {
        WordItem word = MarkerWordItem.createStartOfHost();
        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = word.encode(buffer);

        buffer.flip();

        assertEquals(1, count, "Serialization count");

        assertType(buffer, 4, 0);

        assertEquals(0, buffer.get(), "Index length");
        assertEquals(9, buffer.get(), "Word length");
        assertEquals(9, buffer.remaining(), "Word length");
        assertEquals('S', buffer.get());
        assertEquals('t', buffer.get());
        assertEquals('A', buffer.get());
        assertEquals('r', buffer.get());
        assertEquals('T', buffer.get());
        assertEquals('h', buffer.get());
        assertEquals('O', buffer.get());
        assertEquals('s', buffer.get());
        assertEquals('T', buffer.get());
    }

    @Test
    void testEndHostMarkerEncoding() {
        WordItem word = MarkerWordItem.createEndOfHost();

        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = word.encode(buffer);

        buffer.flip();

        assertEquals(1, count, "Serialization count");

        assertType(buffer, 4, 0);

        assertEquals(0, buffer.get(), "Index length");
        assertEquals(7, buffer.get(), "Word length");
        assertEquals(7, buffer.remaining(), "Word length");
        assertEquals('E', buffer.get());
        assertEquals('n', buffer.get());
        assertEquals('D', buffer.get());
        assertEquals('h', buffer.get());
        assertEquals('O', buffer.get());
        assertEquals('s', buffer.get());
        assertEquals('T', buffer.get());
    }

    @Test
    void testFilterWordItemEncoding() {
        WordItem word = new WordItem("test");

        word.setFilter(true);
        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = word.encode(buffer);

        buffer.flip();

        assertEquals(1, count, "Serialization count");

        assertType(buffer, 4, 4);
        assertEquals(0x08, buffer.get());

        assertEquals(0, buffer.get(), "Index length");
        assertEquals(4, buffer.get(), "Word length");
        assertEquals(4, buffer.remaining(), "Word length");
        assertEquals('t', buffer.get());
        assertEquals('e', buffer.get());
        assertEquals('s', buffer.get());
        assertEquals('t', buffer.get());
    }

    @Test
    void testNoRankedNoPositionDataWordItemEncoding() {
        WordItem word = new WordItem("test");
        word.setRanked(false);
        word.setPositionData(false);

        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = word.encode(buffer);

        buffer.flip();

        assertEquals(1, count, "Serialization count");

        assertType(buffer, 4, 4);
        assertEquals(0x05, buffer.get());

        assertEquals(0, buffer.get(), "Index length");
        assertEquals(4, buffer.get(), "Word length");
        assertEquals(4, buffer.remaining(), "Word length");
        assertEquals('t', buffer.get());
        assertEquals('e', buffer.get());
        assertEquals('s', buffer.get());
        assertEquals('t', buffer.get());
    }

    @Test
    void testAndItemEncoding() {
        WordItem a = new WordItem("a");
        WordItem b = new WordItem("b");
        AndItem and = new AndItem();
        and.addItem(a);
        and.addItem(b);

        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = and.encode(buffer);

        buffer.flip();

        assertEquals(3, count, "Serialization count");

        assertType(buffer, 1, 0);

        assertEquals(2, buffer.get(), "And arity");

        assertWord(buffer, "a");
        assertWord(buffer, "b");
    }

    @Test
    void testNearItemEncoding() {
        WordItem a = new WordItem("a");
        WordItem b = new WordItem("b");
        NearItem near = new NearItem(7);
        near.addItem(a);
        near.addItem(b);

        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = near.encode(buffer);

        buffer.flip();

        assertEquals(3, count, "Serialization count");

        assertType(buffer, 11, 0);

        assertEquals(2, buffer.get(), "Near arity");
        assertEquals(7, buffer.get(), "Limit");

        assertWord(buffer, "a");
        assertWord(buffer, "b");
    }

    @Test
    void testONearItemEncoding() {
        WordItem a = new WordItem("a");
        WordItem b = new WordItem("b");
        NearItem onear = new ONearItem(7);
        onear.addItem(a);
        onear.addItem(b);

        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = onear.encode(buffer);

        buffer.flip();

        assertEquals(3, count, "Serialization count");

        assertType(buffer, 12, 0);
        assertEquals(2, buffer.get(), "Near arity");
        assertEquals(7, buffer.get(), "Limit");

        assertWord(buffer, "a");
        assertWord(buffer, "b");
    }

    @Test
    void testEquivItemEncoding() {
        WordItem a = new WordItem("a");
        WordItem b = new WordItem("b");
        EquivItem equiv = new EquivItem();
        equiv.addItem(a);
        equiv.addItem(b);

        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = equiv.encode(buffer);

        buffer.flip();

        assertEquals(3, count, "Serialization count");

        assertType(buffer, 14, 0);
        assertEquals(2, buffer.get(), "Equiv arity");

        assertWord(buffer, "a");
        assertWord(buffer, "b");
    }

    @Test
    void testWandItemEncoding() {
        WordItem a = new WordItem("a");
        WordItem b = new WordItem("b");
        WeakAndItem wand = new WeakAndItem();
        wand.addItem(a);
        wand.addItem(b);

        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = wand.encode(buffer);

        buffer.flip();

        assertEquals(3, count, "Serialization count");

        assertType(buffer, 16, 0);
        assertEquals(2, buffer.get(), "WeakAnd arity");
        assertEquals(100, buffer.getShort() & 0x3fff, "WeakAnd N");
        assertEquals(0, buffer.get());

        assertWord(buffer, "a");
        assertWord(buffer, "b");
    }

    @Test
    void testWandSegmentEncoding() {
        WordItem a = new WordItem("a");
        WordItem b = new WordItem("b");
        WordItem c = new WordItem("c");
        WordItem d = new WordItem("d");
        var bc = new AndSegmentItem("bc", true, true);
        bc.addItem(b);
        bc.addItem(c);
        bc.shouldFoldIntoWand(true);
        WeakAndItem wand = new WeakAndItem();
        wand.setN(321);
        wand.addItem(a);
        wand.addItem(bc);
        wand.addItem(d);
        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = wand.encode(buffer);
        assertEquals(5, count, "Serialization count");
        buffer.flip();
        assertType(buffer, 16, 0);
        assertEquals(4, buffer.get(), "WeakAnd arity");
        assertEquals(321, buffer.getShort() & 0x3fff, "WeakAnd N");
        assertEquals(0, buffer.get());
        assertWord(buffer, "a");
        assertWord(buffer, "b");
        assertWord(buffer, "c");
        assertWord(buffer, "d");
        assertEquals(0, buffer.remaining());
        bc.shouldFoldIntoWand(false);
        buffer = ByteBuffer.allocate(128);
        count = wand.encode(buffer);
        assertEquals(6, count, "Serialization count");
    }

    @Test
    void testPureWeightedStringEncoding() {
        PureWeightedString a = new PureWeightedString("a");
        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = a.encode(buffer);
        buffer.flip();
        assertEquals(3, buffer.remaining(), "Serialization size");
        assertEquals(1, count, "Serialization count");
        assertType(buffer, 19, 0);
        assertString(buffer, a.getString());
    }

    @Test
    void testPureWeightedStringEncodingWithNonDefaultWeight() {
        PureWeightedString a = new PureWeightedString("a", 7);
        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = a.encode(buffer);
        buffer.flip();
        assertEquals(4, buffer.remaining(), "Serialization size");
        assertEquals(1, count, "Serialization count");
        assertType(buffer, 19, 1);
        assertWeight(buffer, 7);
        assertString(buffer, a.getString());
    }

    @Test
    void testPureWeightedIntegerEncoding() {
        PureWeightedInteger a = new PureWeightedInteger(23432568763534865l);
        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = a.encode(buffer);
        buffer.flip();
        assertEquals(9, buffer.remaining(), "Serialization size");
        assertEquals(1, count, "Serialization count");
        assertType(buffer, 20, 0);
        assertEquals(a.getValue(), buffer.getLong(), "Value");
    }

    @Test
    void testPureWeightedLongEncodingWithNonDefaultWeight() {
        PureWeightedInteger a = new PureWeightedInteger(23432568763534865l, 7);
        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = a.encode(buffer);
        buffer.flip();
        assertEquals(10, buffer.remaining(), "Serialization size");
        assertEquals(1, count, "Serialization count");
        assertType(buffer, 20, 1);
        assertWeight(buffer, 7);
        assertEquals(a.getValue(), buffer.getLong(), "Value");
        ;
    }

    @Test
    void testStringInItem() {
        var a = new StringInItem("default");
        a.addToken("foo");
        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = a.encode(buffer);
        buffer.flip();
        // 2 bytes type, 1 byte item count, 1 byte string len, 7 bytes string content
        // 1 byte string len, 3 bytes string content
        assertEquals(15, buffer.remaining(), "Serialization size");
        assertType(buffer, 31, 0);
        assertEquals(1, buffer.get()); // 1 item
        assertString(buffer, "default");
        assertString(buffer, "foo");
    }

    @Test
    void testNumericInItem() {
        var a = new NumericInItem("default");
        a.addToken(42);
        a.addToken(97000000000L);
        ByteBuffer buffer = ByteBuffer.allocate(128);
        int count = a.encode(buffer);
        buffer.flip();
        // 2 bytes type, 1 byte item count, 1 byte string len, 7 bytes string content
        // 16 bytes (2 64-bit integer value)
        assertEquals(27, buffer.remaining(), "Serialization size");
        assertType(buffer, 32, 0);
        assertEquals(2, buffer.get()); // 2 items
        assertString(buffer, "default");
        var array = new ArrayList<Long>();
        array.add(buffer.getLong());
        array.add(buffer.getLong());
        Collections.sort(array);
        assertEquals(42, array.get(0));
        assertEquals(97000000000L, array.get(1));
    }

    private void assertString(ByteBuffer buffer, String word) {
        assertEquals(word.length(), buffer.get(), "Word length");
        for (int i=0; i<word.length(); i++) {
            assertEquals(word.charAt(i), buffer.get(), "Character at " + i);
        }
    }
    private void assertWord(ByteBuffer buffer,String word) {
        assertType(buffer, 4, 0);

        assertEquals(0, buffer.get(), "Index length");
        assertString(buffer, word);
    }

}
