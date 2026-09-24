"""Regenerate the .dta test fixtures with pandas.

    python src/test/resources/fixtures/make_fixtures.py

Every file holds the same three observations; StataReaderTest asserts against
these values, so keep the two in sync. Format 114 cannot hold str# > 244 or
strL, so those columns are only written for 117+.
"""
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
LONG = "x" * 300


def frame(version):
    df = pd.DataFrame(
        {
            "b": pd.array([1, -5, None], dtype="Int8"),
            "i": pd.array([1000, -1000, None], dtype="Int16"),
            "l": pd.array([100000, -100000, None], dtype="Int32"),
            "f": np.array([1.5, -2.25, np.nan], dtype=np.float32),
            "d": np.array([3.125, -1e300, np.nan], dtype=np.float64),
            "s": ["Alice", "Bob", "" if version < 118 else "Zoë"],
            "grade": pd.Categorical(["low", "high", "low"], categories=["low", "high"]),
        }
    )
    if version >= 117:
        df["longs"] = [LONG, "short", ""]
        df["notes"] = ["first note", "", "first note"]
    return df


def write(version, name, byteorder="little"):
    df = frame(version)
    kwargs = {}
    # pandas 2.2 writes big-endian strL (v,o) cells as one u64 instead of
    # v followed by o, contrary to the dta spec, so big-endian files keep
    # "notes" as a plain str#.
    if version >= 117 and byteorder == "little":
        kwargs["convert_strl"] = ["notes"]
    labels = {c: f"label of {c}" for c in df.columns}
    df.to_stata(
        HERE / name,
        write_index=False,
        version=version,
        byteorder=byteorder,
        data_label="Stata4j fixture",
        variable_labels=labels,
        **kwargs,
    )


def write_alias(src_version, dst_version, name):
    """Write a format 120/121 file, which pandas cannot produce directly.

    pandas writes a 118/119 file whose second column "al" is a byte. That column
    is then turned into an alias variable (type 65525) by patching the release,
    the type code, and dropping its byte from every <data> row; the <map>
    offsets of the sections after <data> are shifted to match. This encodes
    the assumption that alias variables occupy no bytes in <data>.
    """
    df = pd.DataFrame(
        {
            "id": np.array([1, 2, 3], dtype=np.int8),
            "al": np.array([0, 0, 0], dtype=np.int8),
            "name": ["a", "bb", "ccc"],
            "notes": ["n1", "", "n1"],
        }
    )
    tmp = HERE / f"_{name}.tmp"
    df.to_stata(tmp, write_index=False, version=src_version, convert_strl=["notes"])
    b = bytearray(tmp.read_bytes())
    tmp.unlink()

    old, new = f"<release>{src_version}</release>", f"<release>{dst_version}</release>"
    b[:] = b.replace(old.encode(), new.encode(), 1)

    types = b.index(b"<variable_types>") + len(b"<variable_types>")
    b[types + 2 : types + 4] = (65525).to_bytes(2, "little")  # variable 2 -> alias

    d0 = b.index(b"<data>") + len(b"<data>")
    d1 = b.index(b"</data>")
    nobs = len(df)
    row = (d1 - d0) // nobs
    alias_offset, alias_width = 1, 1  # after "id" (1 byte), "al" is 1 byte
    rows = b[d0:d1]
    kept = bytearray()
    for r in range(nobs):
        cell = rows[r * row : (r + 1) * row]
        kept += cell[:alias_offset] + cell[alias_offset + alias_width :]
    removed = len(rows) - len(kept)
    b[d0:d1] = kept

    # <map> holds 14 u64 offsets; entries 10-13 (<strls>, <value_labels>,
    # </stata_dta>, end of file) follow <data> and move back by `removed`.
    m = b.index(b"<map>") + len(b"<map>")
    for i in range(10, 14):
        at = m + 8 * i
        off = int.from_bytes(b[at : at + 8], "little")
        b[at : at + 8] = (off - removed).to_bytes(8, "little")

    (HERE / name).write_bytes(bytes(b))


if __name__ == "__main__":
    write(114, "v114.dta")
    write(117, "v117.dta")
    write(118, "v118.dta")
    write(119, "v119.dta")
    write(118, "v118_big.dta", byteorder="big")
    write_alias(118, 120, "v120_alias.dta")
    write_alias(119, 121, "v121_alias.dta")
    for p in sorted(HERE.glob("*.dta")):
        if "alias" not in p.name:  # pandas cannot read 120/121
            pd.read_stata(p)  # round-trip sanity check
        print(p.name, p.stat().st_size)
