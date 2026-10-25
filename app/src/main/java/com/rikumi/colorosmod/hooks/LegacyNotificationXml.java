package com.rikumi.colorosmod.hooks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntUnaryOperator;

final class LegacyNotificationXml {
    static byte[] remap(byte[] original, IntUnaryOperator resource) {
        return remap(original, resource, id -> -1L);
    }

    static byte[] remap(byte[] original, IntUnaryOperator resource,
            java.util.function.IntToLongFunction literal) {
        byte[] bytes = original.clone();
        ByteBuffer xml = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 8 || u16(xml, 0) != 3 || xml.getInt(4) != bytes.length)
            throw new IllegalArgumentException("Invalid binary XML");
        int offset = u16(xml, 2);
        if (offset < 8 || offset > bytes.length) throw new IllegalArgumentException("Invalid XML header");
        while (offset < bytes.length) {
            if (bytes.length - offset < 8) throw new IllegalArgumentException("Truncated XML chunk");
            int type = u16(xml, offset), header = u16(xml, offset + 2), size = xml.getInt(offset + 4);
            if (header < 8 || size < header || size > bytes.length - offset)
                throw new IllegalArgumentException("Invalid XML chunk size");
            int end = offset + size;
            if (type == 0x0180) { // ResXMLTree_resource_map: attribute-name resource IDs.
                if ((size - header) % 4 != 0) throw new IllegalArgumentException("Invalid resource map");
                for (int at = offset + header; at < end; at += 4)
                    xml.putInt(at, resource.applyAsInt(xml.getInt(at)));
            } else if (type == 0x0102) { // ResXMLTree_attrExt plus ResXMLTree_attribute[].
                int ext = offset + header;
                if (end - ext < 20) throw new IllegalArgumentException("Truncated XML element");
                int start = u16(xml, ext + 8), stride = u16(xml, ext + 10), count = u16(xml, ext + 12);
                if (start < 20 || stride < 20 || (long) start + (long) stride * count > end - ext)
                    throw new IllegalArgumentException("Invalid XML attributes");
                for (int i = 0; i < count; i++) {
                    int value = ext + start + i * stride + 12;
                    int valueType = bytes[value + 3] & 255;
                    if (valueType == 1 || valueType == 2 || valueType == 7 || valueType == 8) {
                        int id = xml.getInt(value + 4);
                        long replacement = valueType == 1 ? literal.applyAsLong(id) : -1L;
                        if (replacement != -1L) {
                            bytes[value + 3] = (byte) (replacement >>> 32);
                            xml.putInt(value + 4, (int) replacement);
                        } else xml.putInt(value + 4, resource.applyAsInt(id));
                    }
                }
            }
            offset = end;
        }
        return bytes;
    }

    private static int u16(ByteBuffer bytes, int at) { return bytes.getShort(at) & 0xffff; }
}
