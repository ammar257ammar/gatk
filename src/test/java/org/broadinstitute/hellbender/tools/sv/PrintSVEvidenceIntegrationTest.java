package org.broadinstitute.hellbender.tools.sv;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.util.FileExtensions;
import org.broadinstitute.hellbender.CommandLineProgramTest;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.testutils.IntegrationTestSpec;
import org.broadinstitute.hellbender.utils.codecs.*;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Collections;

public class PrintSVEvidenceIntegrationTest extends CommandLineProgramTest {

    public static final String printEvidenceTestDir = toolsTestDir + "walkers/sv/printevidence";
    public static final SAMSequenceDictionary dict =
            new SAMSequenceDictionary(Collections.singletonList(new SAMSequenceRecord("chr1", 100000000)));

    // these test files were generated by tabix
    // note tabix interval ends are inclusive but GATK's are exclusive
    @DataProvider
    public Object[][] printSVEvidenceCases() {
        return new Object[][]{
                {
                        "print read pairs zipped",
                        printEvidenceTestDir + "/test_hg38" + DiscordantPairEvidenceCodec.FORMAT_SUFFIX + ".gz",
                        DiscordantPairEvidenceCodec.FORMAT_SUFFIX,
                        "chr22:30500000-30550001",
                        printEvidenceTestDir + "/output.test_hg38" + DiscordantPairEvidenceCodec.FORMAT_SUFFIX + ".gz",
                },
                {
                        "print read pairs unzipped",
                        printEvidenceTestDir + "/test_hg38" + DiscordantPairEvidenceCodec.FORMAT_SUFFIX + ".gz",
                        DiscordantPairEvidenceCodec.FORMAT_SUFFIX,
                        "chr22:30500000-30550001",
                        printEvidenceTestDir + "/output.test_hg38" + DiscordantPairEvidenceCodec.FORMAT_SUFFIX,
                },
                {
                    "print split reads zipped",
                    printEvidenceTestDir + "/test_hg38" + SplitReadEvidenceCodec.FORMAT_SUFFIX + ".gz",
                    SplitReadEvidenceCodec.FORMAT_SUFFIX + ".gz",
                    "chr22:30500000-30550001",
                    printEvidenceTestDir + "/output.test_hg38" + SplitReadEvidenceCodec.FORMAT_SUFFIX + ".gz",
                },
                {
                    "print baf zipped",
                    printEvidenceTestDir + "/test_hg38" + BafEvidenceCodec.FORMAT_SUFFIX + ".gz",
                    BafEvidenceCodec.FORMAT_SUFFIX + ".gz",
                    "chr22:30500000-30550001",
                    printEvidenceTestDir + "/output.test_hg38" + BafEvidenceCodec.FORMAT_SUFFIX + ".gz",
                },
                {
                    "print rd zipped",
                    printEvidenceTestDir + "/test_hg38" + DepthEvidenceCodec.FORMAT_SUFFIX + ".gz",
                    DepthEvidenceCodec.FORMAT_SUFFIX + ".gz",
                    "chr22:30500000-30550001",
                    printEvidenceTestDir + "/output.test_hg38" + DepthEvidenceCodec.FORMAT_SUFFIX + ".gz",
                },
                {
                    "print rd unzipped",
                    printEvidenceTestDir + "/test_hg38" + DepthEvidenceCodec.FORMAT_SUFFIX + ".gz",
                    DepthEvidenceCodec.FORMAT_SUFFIX,
                    "chr22:30500000-30550001",
                    printEvidenceTestDir + "/output.test_hg38" + DepthEvidenceCodec.FORMAT_SUFFIX,
                }
        };
    }
    @Test(dataProvider="printSVEvidenceCases")
    public void testPrintSplitReads(final String testName, final String input, final String extension, final String interval, final String output) throws Exception {
        final String args = "--evidence-file " + input
                + " -" + StandardArgumentDefinitions.INTERVALS_SHORT_NAME + " " + interval
                + " --" + StandardArgumentDefinitions.SEQUENCE_DICTIONARY_NAME + " " + FULL_HG38_DICT
                + " -" + StandardArgumentDefinitions.OUTPUT_SHORT_NAME + " %s";
        final IntegrationTestSpec spec = new IntegrationTestSpec(args, Collections.singletonList(output));
        spec.setOutputFileExtension(extension);
        final String expectedIndexExtension = extension.endsWith(".gz") ? FileExtensions.TABIX_INDEX : null;
        spec.executeTest(testName, this, expectedIndexExtension);
    }

    @Test
    public void testCorrectFeatureTypes() throws IOException {
        final IntegrationTestSpec testSpec = new IntegrationTestSpec(
                " -" + StandardArgumentDefinitions.OUTPUT_SHORT_NAME + " %s" +
                        " -" + StandardArgumentDefinitions.FEATURE_SHORT_NAME + " " +
                        packageRootTestDir + "engine/tiny_hg38.baf.bci",
                Collections.singletonList(packageRootTestDir + "engine/tiny_hg38.baf.txt"));
        testSpec.setOutputFileExtension("baf.txt");
        testSpec.executeTest("matching input and output types", this);
    }

    @Test
    public void testIncorrectFeatureTypes() throws IOException {
        final IntegrationTestSpec testSpec = new IntegrationTestSpec(
                " -" + StandardArgumentDefinitions.OUTPUT_SHORT_NAME + " %s" +
                        " -" + StandardArgumentDefinitions.FEATURE_SHORT_NAME + " " +
                        packageRootTestDir + "engine/tiny_hg38.baf.bci",
                1, UserException.class);
        testSpec.setOutputFileExtension("pe.txt");
        testSpec.executeTest("mismatched input and output types", this);
    }

    public static class DummyFeatureSink<F extends SVFeature> implements FeatureSink<F> {
        public int nRecsWritten = 0;
        public void write( F feature ) { nRecsWritten += 1; }
        public void close() {}
    }

    @Test(expectedExceptions = {UserException.class})
    public void testBafViolateUniquenessCriterion() {
        final DummyFeatureSink<BafEvidence> sink = new DummyFeatureSink<>();
        final BafEvidenceSortMerger sortMerger = new BafEvidenceSortMerger(dict, sink);
        sortMerger.write(new BafEvidence("sample", "chr1", 1, .5));
        sortMerger.write(new BafEvidence("sample", "chr1", 1, .4));
        sortMerger.close();
        Assert.assertEquals(sink.nRecsWritten, 0);
    }

    @Test
    public void testBafUniquenessCriterion() {
        final DummyFeatureSink<BafEvidence> sink = new DummyFeatureSink<>();
        final BafEvidenceSortMerger sortMerger = new BafEvidenceSortMerger(dict, sink);
        sortMerger.write(new BafEvidence("sample1", "chr1", 1, .5));
        sortMerger.write(new BafEvidence("sample2", "chr1", 1, .4));
        sortMerger.close();
        Assert.assertEquals(sink.nRecsWritten, 2);
    }

    @Test(expectedExceptions = {UserException.class})
    public void testDepthViolateUniquenessCriterion() {
        final DummyFeatureSink<DepthEvidence> sink = new DummyFeatureSink<>();
        final DepthEvidenceSortMerger sortMerger = new DepthEvidenceSortMerger(dict, sink);
        sortMerger.write(new DepthEvidence("chr1", 1, 101, new int[]{1}));
        sortMerger.write(new DepthEvidence("chr1", 1, 101, new int[]{1}));
        sortMerger.close();
        Assert.assertEquals(sink.nRecsWritten, 0);
    }

    @Test
    public void testDepthUniquenessCriterion() {
        // as long as the data doesn't conflict, the 2 features ought to be folded together
        testDepthUniquenessCriterion(DepthEvidence.MISSING_DATA, 1);
        testDepthUniquenessCriterion(1, DepthEvidence.MISSING_DATA);
        testDepthUniquenessCriterion(DepthEvidence.MISSING_DATA, DepthEvidence.MISSING_DATA);
    }

    public void testDepthUniquenessCriterion( int val1, int val2 ) {
        final DummyFeatureSink<DepthEvidence> sink = new DummyFeatureSink<>();
        final DepthEvidenceSortMerger sortMerger = new DepthEvidenceSortMerger(dict, sink);
        sortMerger.write(new DepthEvidence("chr1", 1, 101, new int[]{val1}));
        sortMerger.write(new DepthEvidence("chr1", 1, 101, new int[]{val2}));
        sortMerger.close();
        Assert.assertEquals(sink.nRecsWritten, 1);
    }

    @Test(expectedExceptions = {UserException.class})
    public void testLocusDepthViolateUniquenessCriterion() {
        final DummyFeatureSink<LocusDepth> sink = new DummyFeatureSink<>();
        final LocusDepthSortMerger sortMerger = new LocusDepthSortMerger(dict, sink);
        sortMerger.write(new LocusDepth("chr1", 1, "sample", (byte)'A', 1, 0, 0, 0));
        sortMerger.write(new LocusDepth("chr1", 1, "sample", (byte)'A', 0, 1, 0, 0));
        sortMerger.close();
        Assert.assertEquals(sink.nRecsWritten, 0);
    }

    @Test
    public void testLocusDepthUniquenessCriterion() {
        final DummyFeatureSink<LocusDepth> sink = new DummyFeatureSink<>();
        final LocusDepthSortMerger sortMerger = new LocusDepthSortMerger(dict, sink);
        sortMerger.write(new LocusDepth("chr1", 1, "sample1", (byte)'A', 1, 0, 0, 0));
        sortMerger.write(new LocusDepth("chr1", 1, "sample2", (byte)'A', 0, 1, 0, 0));
        sortMerger.close();
        Assert.assertEquals(sink.nRecsWritten, 2);
    }

    @Test(expectedExceptions = {UserException.class})
    public void testSplitReadViolateUniquenessCriterion() {
        final DummyFeatureSink<SplitReadEvidence> sink = new DummyFeatureSink<>();
        final SplitReadEvidenceSortMerger sortMerger = new SplitReadEvidenceSortMerger(dict, sink);
        sortMerger.write(new SplitReadEvidence("sample", "chr1", 1, 1, true));
        sortMerger.write(new SplitReadEvidence("sample", "chr1", 1, 1, true));
        sortMerger.close();
        Assert.assertEquals(sink.nRecsWritten, 0);
    }

    @Test
    public void testSplitReadUniquenessCriterion() {
        final DummyFeatureSink<SplitReadEvidence> sink = new DummyFeatureSink<>();
        final SplitReadEvidenceSortMerger sortMerger = new SplitReadEvidenceSortMerger(dict, sink);
        sortMerger.write(new SplitReadEvidence("sample1", "chr1", 1, 1, true));
        sortMerger.write(new SplitReadEvidence("sample2", "chr1", 1, 1, true));
        sortMerger.write(new SplitReadEvidence("sample1", "chr1", 1, 1, false));
        sortMerger.write(new SplitReadEvidence("sample2", "chr1", 1, 1, false));
        sortMerger.close();
        Assert.assertEquals(sink.nRecsWritten, 4);
    }
}
