package com.example.ussdGateway.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvZipWriterTest {

    private static String readEntry(byte[] zipBytes, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("entry not found: " + name);
    }

    @Test
    void writesQuotedEscapedAndGuardedRowsAcrossEntries() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CsvZipWriter csv = new CsvZipWriter(out)) {
            csv.startEntry("a.csv");
            csv.writeRow("id", "text");
            csv.writeRow("1", "he said \"hi\", ok");
            csv.writeRow("2", "=SUM(A1)");
            csv.writeRow("+249123456789", "-5");
            csv.writeRow("line1\nline2", null);
            csv.startEntry("b.csv");
            csv.writeRow("x");
            csv.finish();
        }
        String a = readEntry(out.toByteArray(), "a.csv");
        assertTrue(a.startsWith("\uFEFF"));
        assertEquals("\uFEFFid,text\r\n"
                + "1,\"he said \"\"hi\"\", ok\"\r\n"
                + "2,'=SUM(A1)\r\n"
                + "+249123456789,-5\r\n"
                + "\"line1\nline2\",\r\n", a);
        assertEquals("\uFEFFx\r\n", readEntry(out.toByteArray(), "b.csv"));
    }
}
