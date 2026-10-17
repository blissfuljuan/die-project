package com.blissfuljuan.dieapi.roster.service.reader;

import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.roster.model.GridRow;
import com.blissfuljuan.dieapi.roster.model.RosterFileType;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.blissfuljuan.dieapi.roster.model.rule.RosterLimits.MAX_COLUMNS;
import static com.blissfuljuan.dieapi.roster.model.rule.RosterLimits.cap;
import static com.blissfuljuan.dieapi.roster.model.rule.RosterLimits.rawRowLimit;

/**
 * XLSX: a real ZIP container, first sheet only, every cell read as text, formulas never evaluated (the row is
 * flagged). The container is pre-scanned in constant memory BEFORE Apache POI is allowed to load it (entry count,
 * unpacked size, rows declared per worksheet), so zip bombs and million-row sheets are refused cheaply.
 */
@Component
public class XlsxRosterReader implements RosterFileReader {

    private static final Logger log = LoggerFactory.getLogger(XlsxRosterReader.class);

    /** Limits applied to the unpacked workbook before Apache POI is allowed to load it. */
    private static final int MAX_ZIP_ENTRIES = 200;
    private static final long MAX_UNPACKED_BYTES = 30L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;
    private static final byte[] ROW_MARKER = "<row ".getBytes(StandardCharsets.US_ASCII);

    static {
        // POI-level defence in depth (its default compression-ratio check stays on; tightening it rejects
        // ordinary small workbooks). preflight() already bounds what POI is ever allowed to unpack.
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
    }

    private final int maxRows;

    public XlsxRosterReader(@Value("${die.roster.max-rows:500}") int maxRows) {
        this.maxRows = maxRows;
    }

    @Override
    public RosterFileType type() {
        return RosterFileType.XLSX;
    }

    @Override
    public List<GridRow> read(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw ApiException.badRequest(ErrorCode.XLSX_INVALID, "The file is not a valid .xlsx workbook");
        }
        preflight(bytes);
        List<GridRow> rows = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw ApiException.badRequest(ErrorCode.XLSX_NO_SHEETS, "The workbook has no sheets");
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > rawRowLimit(maxRows)) {
                throw ApiException.badRequest(ErrorCode.TOO_MANY_ROWS, "Too many rows (maximum " + maxRows + ")");
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (int r = Math.max(sheet.getFirstRowNum(), 0); r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                boolean formula = false;
                if (row != null) {
                    int last = Math.min(row.getLastCellNum(), MAX_COLUMNS);
                    for (int c = 0; c < last; c++) {
                        Cell cell = row.getCell(c);
                        if (cell == null) {
                            cells.add("");
                        } else if (cell.getCellType() == CellType.FORMULA) {
                            formula = true; // never evaluated, never trusted
                            cells.add("");
                        } else {
                            cells.add(cap(formatter.formatCellValue(cell).trim()));
                        }
                    }
                }
                rows.add(new GridRow(r + 1, cells, formula));
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.debug("XLSX parse failure", e);
            throw ApiException.badRequest(ErrorCode.XLSX_UNREADABLE, "The workbook could not be read");
        }
        return rows;
    }

    /**
     * Streams the ZIP container once, discarding the content, to enforce limits BEFORE Apache POI builds its
     * in-memory model: entry count, total unpacked size, and the number of rows declared by each worksheet.
     */
    private void preflight(byte[] bytes) {
        long total = 0;
        int entries = 0;
        int rowLimit = rawRowLimit(maxRows);
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw ApiException.badRequest(ErrorCode.XLSX_TOO_MANY_PARTS, "The workbook has too many parts");
                boolean worksheet = entry.getName().startsWith("xl/worksheets/") && entry.getName().endsWith(".xml");
                long entryBytes = 0;
                int rows = 0;
                int matched = 0;                       // progress through the marker "<row " across buffer boundaries
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > MAX_ENTRY_BYTES || total > MAX_UNPACKED_BYTES) {
                        throw ApiException.badRequest(ErrorCode.XLSX_TOO_LARGE_UNPACKED, "The workbook is too large once unpacked");
                    }
                    if (worksheet) {
                        for (int i = 0; i < read; i++) {
                            byte b = buffer[i];
                            if (b == ROW_MARKER[matched]) {
                                if (++matched == ROW_MARKER.length) {
                                    matched = 0;
                                    if (++rows > rowLimit) throw ApiException.badRequest(ErrorCode.TOO_MANY_ROWS, "Too many rows (maximum " + maxRows + ")");
                                }
                            } else {
                                matched = b == ROW_MARKER[0] ? 1 : 0;
                            }
                        }
                    }
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.debug("XLSX preflight failure", e);
            throw ApiException.badRequest(ErrorCode.XLSX_UNREADABLE, "The workbook could not be read");
        }
    }
}
