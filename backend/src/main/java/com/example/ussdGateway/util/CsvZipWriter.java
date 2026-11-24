package com.example.ussdGateway.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams one or more CSV files into a single ZIP archive without temp files.
 * Output is UTF-8 with BOM (so Excel detects the encoding) and RFC 4180 quoting.
 */
public final class CsvZipWriter implements AutoCloseable {

    private final ZipOutputStream zip;
    private final Writer writer;
    private boolean entryOpen;

    public CsvZipWriter(OutputStream out) {
        this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        this.zip.setLevel(Deflater.BEST_SPEED);
        this.writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 64 * 1024);
    }

    public void startEntry(String name) throws IOException {
        endEntry();
        zip.putNextEntry(new ZipEntry(name));
        entryOpen = true;
        writer.write('\uFEFF');
    }

    public void writeRow(String... cols) throws IOException {
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) writer.write(',');
            writeField(cols[i]);
        }
        writer.write("\r\n");
    }

    public void writeRow(List<String> cols) throws IOException {
        writeRow(cols.toArray(new String[0]));
    }

    public void blankRow() throws IOException {
        writer.write("\r\n");
    }

    private void writeField(String value) throws IOException {
        if (value == null || value.isEmpty()) return;
        String v = guardFormula(value);
        boolean quote = false;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == ',' || ch == '"' || ch == '\r' || ch == '\n') {
                quote = true;
                break;
            }
        }
        if (!quote) {
            writer.write(v);
            return;
        }
        writer.write('"');
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '"') writer.write('"');
            writer.write(ch);
        }
        writer.write('"');
    }

    /** Prevents spreadsheet formula injection while leaving phone numbers such as +249... intact. */
    private static String guardFormula(String v) {
        char first = v.charAt(0);
        if (first == '=' || first == '@' || first == '\t' || first == '\r') {
            return "'" + v;
        }
        if ((first == '+' || first == '-') && v.length() > 1 && !Character.isDigit(v.charAt(1))) {
            return "'" + v;
        }
        return v;
    }

    private void endEntry() throws IOException {
        if (entryOpen) {
            writer.flush();
            zip.closeEntry();
            entryOpen = false;
        }
    }

    public void finish() throws IOException {
        endEntry();
        writer.flush();
        zip.finish();
    }

    @Override
    public void close() {
        try {
            zip.close();
        } catch (IOException ignored) {
            // underlying stream already failed or closed
        }
    }
}
