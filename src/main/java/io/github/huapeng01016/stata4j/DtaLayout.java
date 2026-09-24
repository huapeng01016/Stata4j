package io.github.huapeng01016.stata4j;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Field widths that differ between .dta releases. All sizes are in bytes.
 *
 * @param strlVBytes  width of v in a strL (v,o) data cell; o takes the rest of the 8 bytes
 * @param gsoOBytes   width of o in a GSO record header (v is always 4 bytes)
 */
record DtaLayout(
        int release,
        boolean tagged,
        Charset charset,
        int varNameLen,
        int formatLen,
        int labelNameLen,
        int varLabelLen,
        int kBytes,
        int nBytes,
        int dataLabelLenBytes,
        int sortEntryBytes,
        int strlVBytes,
        int gsoOBytes) {

    static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /** Width of the legacy (113-115) dataset label and timestamp fields. */
    static final int LEGACY_DATA_LABEL_LEN = 81;
    static final int LEGACY_TIMESTAMP_LEN = 18;

    /** Alias variables (type code 65525) exist only in formats 120 and 121. */
    boolean allowsAlias() {
        return release >= 120;
    }

    static DtaLayout forRelease(int release) throws StataFormatException {
        switch (release) {
            case 113: return new DtaLayout(113, false, WINDOWS_1252, 33, 12, 33, 81, 2, 4, 0, 2, 0, 0);
            case 114:
            case 115: return new DtaLayout(release, false, WINDOWS_1252, 33, 49, 33, 81, 2, 4, 0, 2, 0, 0);
            case 117: return new DtaLayout(117, true, WINDOWS_1252, 33, 49, 33, 81, 2, 4, 1, 2, 4, 4);
            // 120/121 are 118/119 plus the alias variable type (Stata 18+).
            case 118:
            case 120: return new DtaLayout(release, true, StandardCharsets.UTF_8, 129, 57, 129, 321, 2, 8, 2, 2, 2, 8);
            case 119:
            case 121: return new DtaLayout(release, true, StandardCharsets.UTF_8, 129, 57, 129, 321, 4, 8, 2, 4, 3, 8);
            default: throw new StataFormatException("Unsupported Stata format: " + release);
        }
    }
}
