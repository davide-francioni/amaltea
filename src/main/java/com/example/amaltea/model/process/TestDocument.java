package com.example.amaltea.model.process;

import java.nio.file.Path;

/**
 * One PDF of the test corpus.
 * <p>
 * The ten documents are software architecture reports graded 30 or 30 cum laude, selected on
 * the same criterion as the CAPRA paper. They are the <em>inputs</em>: CAPRA produces the
 * reference standard output on them, and AMALTEA's job is to reproduce that output with the
 * SLM system using the smallest possible model.
 *
 * @param id       stable identifier used as the archive folder name
 * @param filename original file name
 * @param path     location on disk
 * @param role     evaluation or training; only relevant at L3
 */
public record TestDocument(
        String id,
        String filename,
        Path path,
        DocumentRole role
) {}
