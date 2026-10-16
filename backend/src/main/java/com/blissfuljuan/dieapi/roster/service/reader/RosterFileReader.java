package com.blissfuljuan.dieapi.roster.service.reader;

import com.blissfuljuan.dieapi.roster.model.GridRow;
import com.blissfuljuan.dieapi.roster.model.RosterFileType;

import java.util.List;

/**
 * Reads one upload format into raw grid rows. Input is untrusted: implementations check the content
 * (not just the extension), enforce their format's size limits and never evaluate formulas.
 */
public interface RosterFileReader {

    RosterFileType type();

    List<GridRow> read(byte[] bytes);
}
