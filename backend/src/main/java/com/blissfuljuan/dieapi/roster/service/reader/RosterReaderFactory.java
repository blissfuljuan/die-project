package com.blissfuljuan.dieapi.roster.service.reader;

import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.roster.model.RosterFileType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/** Picks the reader for an upload from its file name; fails at startup if a supported type has no reader. */
@Component
public class RosterReaderFactory {

    private final Map<RosterFileType, RosterFileReader> readers = new EnumMap<>(RosterFileType.class);

    public RosterReaderFactory(Collection<RosterFileReader> all) {
        for (RosterFileReader r : all) {
            if (readers.put(r.type(), r) != null) throw new IllegalStateException("Two roster readers for " + r.type());
        }
        for (RosterFileType t : RosterFileType.values()) {
            if (!readers.containsKey(t)) throw new IllegalStateException("No roster reader for " + t);
        }
    }

    public RosterFileReader forFile(String fileName) {
        RosterFileType type = RosterFileType.fromFileName(fileName);
        if (type == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_FILE_TYPE,
                "Only .csv and .xlsx files are supported");
        }
        return readers.get(type);
    }
}
