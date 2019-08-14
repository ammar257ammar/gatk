package org.broadinstitute.hellbender.tools;

import org.broadinstitute.barclay.argparser.BetaFeature;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.programgroups.CoverageAnalysisProgramGroup;
import org.broadinstitute.hellbender.engine.FeatureContext;
import org.broadinstitute.hellbender.engine.MultiplePassReadWalker;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.engine.filters.ReadFilter;
import org.broadinstitute.hellbender.engine.filters.ReadFilterLibrary;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.tools.spark.utils.SetSizeUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.function.LongFunction;

@DocumentedFeature
@CommandLineProgramProperties(
        summary = "experiment",
        oneLineSummary = "experiment",
        usageExample = "gatk LocalAssembler",
        programGroup = CoverageAnalysisProgramGroup.class
)
@BetaFeature
public class LocalAssembler extends MultiplePassReadWalker {
    private static int nReads;
    public static final int KSIZE = 31;
    public static final long KMASK = (1L << 2*KSIZE) - 1L;
    public static final byte QMIN = 24;

    @Override public List<ReadFilter> getDefaultReadFilters() {
        return Collections.singletonList(ReadFilterLibrary.PRIMARY_LINE);
    }

    @Override public void traverseReads() {
        final KmerSet<KmerAdjacency> kmerAdjacencySet = new KmerSet<>(1000000);
        forEachRead( (read, ref, feature) -> {
            final byte[] calls = read.getBasesNoCopy();
            final byte[] quals = read.getBaseQualitiesNoCopy();
            KmerAdjacency.kmerize(calls, quals, nReads++, QMIN, kmerAdjacencySet); });

        final List<ContigImpl> contigs = buildContigs(kmerAdjacencySet);
        connectContigs(contigs);
        removeThinContigs(contigs, kmerAdjacencySet);
        weldPipes(contigs);
        markComponents(contigs);
        nameContigs(contigs);
        nReads = 0;
        forEachRead( (read, ref, feature) -> {
            final byte[] calls = read.getBasesNoCopy();
            long kVal = 0;
            int count = 0;
            final List<PathPart> path = new ArrayList<>();
            PathPart currentPathPart = null;
            for ( final byte call : calls ) {
                kVal <<= 2;
                switch ( call ) {
                    case 'A': case 'a': break;
                    case 'C': case 'c': kVal += 1; break;
                    case 'G': case 'g': kVal += 2; break;
                    case 'T': case 't': kVal += 3; break;
                    default: count = -1; break;
                }
                if ( ++count >= KSIZE ) {
                    final KmerAdjacency kmer = KmerAdjacency.find(kVal & KMASK, kmerAdjacencySet);
                    if ( kmer == null ) {
                        if ( currentPathPart == null || !currentPathPart.getContigName().equals(PathPart.NO_KMER) ) {
                            currentPathPart = new PathPart(PathPart.NO_KMER, 0, 0);
                            path.add(currentPathPart);
                        } else {
                            currentPathPart.extendPath();
                        }
                    } else {
                        final Contig contig = kmer.getContig();
                        if ( contig == null ) {
                            if ( currentPathPart == null ||
                                    !currentPathPart.getContigName().equals(PathPart.X_CONTIG) ) {
                                currentPathPart = new PathPart(PathPart.X_CONTIG, 0, 0);
                                path.add(currentPathPart);
                            } else {
                                currentPathPart.extendPath();
                            }
                        } else if ( currentPathPart == null ||
                                !currentPathPart.getContigName().equals(contig.toString()) ) {
                            currentPathPart = new PathPart(contig.toString(), contig.size(), kmer.getContigOffset());
                            path.add(currentPathPart);
                        } else {
                            currentPathPart.extendPath();
                        }
                    }
                }
            }
            //TODO: remove this
            if ( nReads == 242 ) {
                System.out.println("hello");
            }
            System.out.println(nReads++ + ": " + path);
        });
        //phaseBubbles(contigs);
        dumpDOT(contigs, "assembly.dot");
        writeContigs(contigs);
    }

    private static List<ContigImpl> buildContigs( final KmerSet<KmerAdjacency> kmerAdjacencySet ) {
        final List<ContigImpl> contigs = new ArrayList<>();
        for ( final KmerAdjacency kmerAdjacency : kmerAdjacencySet ) {
            if ( kmerAdjacency.getContig() == null ) {
                ContigImpl contig = null;
                final KmerAdjacency predecessor = kmerAdjacency.getSolePredecessor();
                if ( predecessor == null || predecessor.getSuccessorCount() > 1 ) {
                    contig = new ContigImpl(kmerAdjacency);
                } else {
                    final KmerAdjacency successor = kmerAdjacency.getSoleSuccessor();
                    if ( successor == null || successor.getPredecessorCount() > 1 ) {
                        contig = new ContigImpl(kmerAdjacency.rc());
                    }
                }
                if ( contig != null ) {
                    contigs.add(contig);
                }
            }
        }
        return contigs;
    }

    private static List<ContigImpl> connectContigs( final List<ContigImpl> contigs ) {
        final int nContigs = contigs.size();
        final KmerSet<ContigEndKmer> contigEnds = new KmerSet<>(2*nContigs);
        for ( int contigId = 0; contigId != nContigs; ++contigId ) {
            final ContigImpl contig = contigs.get(contigId);
            final KmerAdjacency fwdKmer = contig.getFirstKmer();
            final KmerAdjacency revKmer = contig.getLastKmer().rc();
            if ( fwdKmer == revKmer ) {
                contigEnds.findOrAdd(fwdKmer.getKVal(), kVal -> new ContigEndKmer(kVal, contig, ContigOrientation.BOTH));
            } else {
                contigEnds.findOrAdd(fwdKmer.getKVal(), kVal -> new ContigEndKmer(kVal, contig, ContigOrientation.FWD));
                contigEnds.findOrAdd(revKmer.getKVal(), kVal -> new ContigEndKmer(kVal, contig, ContigOrientation.REV));
            }
        }

        for ( int contigId = 0; contigId != nContigs; ++contigId ) {
            final Contig contig = contigs.get(contigId);

            final KmerAdjacency start = contig.getFirstKmer();
            final int predecessorCount = start.getPredecessorCount();
            if ( predecessorCount > 0 ) {
                final List<Contig> predecessors = contig.getPredecessors();
                final int mask = start.getPredecessorMask();
                for ( int call = 0; call != 4; ++call ) {
                    if ( (mask & (1 << call)) != 0 ) {
                        final ContigEndKmer contigEndKmer =
                                contigEnds.find(KmerAdjacency.reverseComplement(start.getPredecessorVal(call)));
                        switch ( contigEndKmer.getContigOrientation() ) {
                            case FWD:
                                predecessors.add(contigEndKmer.getContig().rc());
                                break;
                            case REV:
                                predecessors.add(contigEndKmer.getContig());
                                break;
                            case BOTH:
                                predecessors.add(contigEndKmer.getContig());
                                predecessors.add(contigEndKmer.getContig().rc());
                                break;
                        }
                    }
                }
            }

            final KmerAdjacency end = contig.getLastKmer();
            final int successorCount = end.getSuccessorCount();
            if ( successorCount > 0 ) {
                final List<Contig> successors = contig.getSuccessors();
                final int mask = end.getSuccessorMask();
                for ( int call = 0; call != 4; ++call ) {
                    if ( (mask & (1 << call)) != 0 ) {
                        final ContigEndKmer contigEndKmer = contigEnds.find(end.getSuccessorVal(call));
                        switch ( contigEndKmer.getContigOrientation() ) {
                            case FWD:
                                successors.add(contigEndKmer.getContig());
                                break;
                            case REV:
                                successors.add(contigEndKmer.getContig().rc());
                                break;
                            case BOTH:
                                successors.add(contigEndKmer.getContig());
                                successors.add(contigEndKmer.getContig().rc());
                                break;
                        }
                    }
                }
            }
        }

        return contigs;
    }

    private static void removeThinContigs( final List<ContigImpl> contigs, final KmerSet<KmerAdjacency> kmerAdjacencySet ) {
        contigs.removeIf(contig -> {
            if ( contig.getMaxObservations() == 1 ) {
                final KmerAdjacency firstKmer = contig.getFirstKmer();
                firstKmer.clearPredecessorMask();
                final int firstKmerFinalCall = firstKmer.getFinalCall();
                for ( final Contig predecessor : contig.getPredecessors() ) {
                    predecessor.getLastKmer().removeSuccessor(firstKmerFinalCall, kmerAdjacencySet);
                    if ( !predecessor.getSuccessors().remove(contig) ) {
                        throw new GATKException("failed to find predecessor link");
                    }
                }

                final KmerAdjacency lastKmer = contig.getLastKmer();
                lastKmer.clearSuccessorMask();
                final int lastKmerInitialCall = lastKmer.getInitialCall();
                for ( final Contig successor : contig.getSuccessors() ) {
                    successor.getFirstKmer().removePredecessor(lastKmerInitialCall, kmerAdjacencySet);
                    if ( !successor.getPredecessors().remove(contig) ) {
                        throw new GATKException("failed to find successor link");
                    }
                }

                updateKmerContig( contig, null, 0 );
                contig.setName(-1);

                return true;
            }
            return false;
        });
    }

    private static int updateKmerContig( final Contig oldContig, final Contig newContig, final int initialOffset ) {
        int offset = initialOffset;
        KmerAdjacency kmer = oldContig.getFirstKmer();
        do {
            kmer.setContig(newContig, offset);
            if ( newContig != null ) offset += 1;
        } while ( (kmer = kmer.getSoleSuccessor()) != null && kmer.getContig() == oldContig );
        return offset;
    }

    private static void weldPipes( final List<ContigImpl> contigs ) {
        for ( int contigId = 0; contigId != contigs.size(); ++contigId ) {
            final ContigImpl contig = contigs.get(contigId);
            if ( contig.getPredecessors().size() == 1 ) {
                final Contig predecessor = contig.getPredecessors().get(0);
                if ( predecessor.getSuccessors().size() == 1 ) {
                    contigs.set(contigId, join(predecessor, contig));
                    contigs.remove(predecessor.canonical());
                    contigId -= 1; // reconsider the new contig -- there might be more joining possible
                    continue;
                }
            }
            if ( contig.getSuccessors().size() == 1 ) {
                final Contig successor = contig.getSuccessors().get(0);
                if ( successor.getPredecessors().size() == 1 ) {
                    contigs.set(contigId, join(contig, successor));
                    contigs.remove(successor.canonical());
                    contigId -= 1; // reconsider the new contig -- there might be more joining possible
                    // don't need this continue, as it's the last statement in the loop
                    // continue;
                }
            }
        }
    }

    private static ContigImpl join( final Contig predecessor, final Contig successor ) {
        final ContigImpl joinedContig = new ContigImpl(predecessor, successor);
        for ( final Contig contig : joinedContig.getPredecessors() ) {
            final List<Contig> successorContigs = contig.getSuccessors();
            successorContigs.set(successorContigs.indexOf(predecessor), joinedContig);
        }
        for ( final Contig contig : joinedContig.getSuccessors() ) {
            final List<Contig> predecessorContigs = contig.getPredecessors();
            predecessorContigs.set(predecessorContigs.indexOf(successor), joinedContig);
        }
        updateKmerContig(successor, joinedContig, updateKmerContig(predecessor, joinedContig, 0));
        predecessor.canonical().setName(-2);
        successor.canonical().setName(-3);
        return joinedContig;
    }

    private static int markComponents( final List<ContigImpl> contigs ) {
        int componentId = 0;
        for ( final ContigImpl contig : contigs ) {
            if ( contig.getComponentId() == 0 ) {
                contig.setComponentId(++componentId);
                markSuccessorComponents(contig);
                markSuccessorComponents(contig.rc());
            }
        }
        return componentId; // this is the total number of components
    }

    private static void markSuccessorComponents( final Contig contig ) {
        final int componentId = contig.getComponentId();
        for ( final Contig successor : contig.getSuccessors() ) {
            if ( successor.getComponentId() != componentId ) {
                successor.canonical().setComponentId(componentId);
                markSuccessorComponents(successor);
                markSuccessorComponents(successor.rc());
            }
        }
    }

    private static void phaseBubbles( final List<ContigImpl> contigs ) {
        for ( final Contig contig : contigs ) {
            final List<Contig> predecessors = contig.getPredecessors();
            if ( predecessors.size() > 1 ) {
                final List<Contig> successors = contig.getSuccessors();
                if ( successors.size() > 1 ) {
                    final List<List<Integer>> predecessorsObservations = new ArrayList<>(predecessors.size());
                    for ( final Contig predecessor : predecessors ) {
                        predecessorsObservations.add(predecessor.getLastKmer().getObservations());
                    }
                    final List<List<Integer>> successorsObservations = new ArrayList<>(successors.size());
                    for ( final Contig successor : successors ) {
                        successorsObservations.add(successor.getFirstKmer().getObservations());
                    }
                    System.out.print(contig);
                    for ( final List<Integer> list1 : predecessorsObservations ) {
                        for ( final List<Integer> list2 : successorsObservations ) {
                            System.out.print('\t');
                            System.out.print(commonCount(list1, list2));
                        }
                        System.out.println();
                    }
                }
            }
        }
    }

    private static int commonCount( final List<Integer> list1, final List<Integer> list2 ) {
        final Iterator<Integer> itr2 = list2.iterator();
        if ( !itr2.hasNext() ) return 0;
        int ele2 = itr2.next();
        int result = 0;
        for ( final int ele1 : list1 ) {
            while ( ele2 <= ele1 ) {
                if ( ele1 == ele2 ) result += 1;
                if ( !itr2.hasNext() ) return result;
                ele2 = itr2.next();
            }
        }
        return result;
    }

    private static void nameContigs( final List<ContigImpl> contigs ) {
        int id = 0;
        for ( final ContigImpl contig : contigs ) {
            contig.setName(++id);
        }
    }

    private static void dumpDOT( final List<ContigImpl> contigs, final String fileName ) {
        try ( final BufferedWriter writer = new BufferedWriter(new FileWriter(fileName)) ) {
            writer.write("digraph {\n");
            for ( final Contig contig : contigs ) {
                final double width = contig.getSequence().length() / 100.;
                writer.write(contig + " [width=" + width + "]\n");
                writer.write( contig.rc() + " [width=" + width + "]\n");
            }
            for ( final Contig contig : contigs ) {
                for ( final Contig predecessor : contig.getPredecessors() ) {
                    writer.write(contig.rc() + " -> " + predecessor.rc() + "\n");
                }
                for ( final Contig successor : contig.getSuccessors() ) {
                    writer.write(contig + " -> " + successor + "\n");
                }
            }
            writer.write("}\n");
        } catch ( final IOException ioe ) {
            throw new GATKException("Failed to write assembly DOT file.", ioe);
        }
    }

    private static void writeContigs( final List<ContigImpl> contigs ) {
        for ( final Contig contig : contigs ) {
            final List<Contig> predecessors = contig.getPredecessors();
            final String predecessorDescription;
            if ( predecessors.size() == 0 ) {
                predecessorDescription = "\tnone";
            } else {
                final StringBuilder sb = new StringBuilder();
                char prefix = '\t';
                for ( final Contig predecessor : predecessors ) {
                    sb.append(prefix);
                    prefix = ',';
                    sb.append(predecessor);
                }
                predecessorDescription = sb.toString();
            }
            final List<Contig> successors = contig.getSuccessors();
            final String successorDescription;
            if ( successors.size() == 0 ) {
                successorDescription = "\tnone";
            } else {
                final StringBuilder sb = new StringBuilder();
                char prefix = '\t';
                for ( final Contig successor : successors ) {
                    sb.append(prefix);
                    prefix = ',';
                    sb.append(successor);
                }
                successorDescription = sb.toString();
            }
            System.out.println(
                    contig + predecessorDescription + successorDescription + "\t" +
                            contig.getMaxObservations() + "\t" +
                            contig.getSequence().length() + "\t" +
                            contig.getSequence());
        }
    }

    public static class Kmer {
        private final long kVal;

        public Kmer( final long kVal ) { this.kVal = kVal; }

        public long getKVal() { return kVal; }
        public boolean isCanonical() { return isCanonical(kVal); }
        public int getInitialCall() { return (int)(kVal >> (KSIZE*2 - 2)) & 3; }
        public int getFinalCall() { return (int)kVal & 3; }

        public static boolean isCanonical( final long val ) {
            return (val & (1L << KSIZE)) == 0L;
        }
    }

    public static final class KmerSet<KMER extends Kmer> implements Iterable<KMER> {
        private int capacity;
        private int size;
        // unused buckets contain null.  (this data structure does not support null entries.)
        // if the bucket is unused, the corresponding status byte is irrelevant, but is always set to 0.
        private KMER[] buckets;
        // format of the status bytes:
        // high bit set indicates that the bucket contains a "chain head" (i.e., an entry that naturally belongs in the
        // corresponding bucket).  high bit not set indicates a "squatter" (i.e., an entry that got placed here through the
        // collision resolution methodology).  we use Byte.MIN_VALUE (i.e., 0x80) to pick off this bit.
        // low 7 bits give the (unsigned) offset from the current entry to the next entry in the collision resolution chain.
        // if the low 7 bits are 0, then we'd be pointing at ourselves, which is nonsense, so that particular value marks
        // "end of chain" instead.  we use Byte.MAX_VALUE (i.e., 0x7f) to pick off these bits.
        private byte[] status;

        private static final double LOAD_FACTOR = .85;
        private static final int SPREADER = 241;

        public KmerSet( final int capacity ) {
            this.capacity = computeCapacity(capacity);
            this.size = 0;
            this.buckets = makeBuckets(this.capacity);
            this.status = new byte[this.capacity];
        }

        public final void clear() {
            Arrays.fill(buckets, null);
            Arrays.fill(status, (byte)0);
            size = 0;
        }

        public Iterator<KMER> iterator() { return new Itr(); }

        public KMER find( final long kVal ) {
            return findOrAdd(kVal, k -> null);
        }

        public KMER findOrAdd( final long kVal, final LongFunction<KMER> producer  ) {
            try {
                return findOrAddInternal(kVal, producer);
            } catch ( final HopscotchException he ) {
                resize();
                return findOrAddInternal(kVal, producer);
            }
        }

        private KMER findOrAddInternal( final long kVal, final LongFunction<KMER> producer ) {
            final int bucketIndex = bucketForKVal(kVal);
            if ( !isChainHead(bucketIndex) ) {
                final KMER entry = producer.apply(kVal);
                if ( entry != null ) {
                    insert(entry, bucketIndex);
                }
                return entry;
            }
            KMER entry = buckets[bucketIndex];
            if ( kVal == entry.getKVal() ) {
                return entry;
            }
            int offset;
            int chainIndex = bucketIndex;
            while ( (offset = getOffset(chainIndex)) != 0 ) {
                chainIndex = getIndex(chainIndex, offset);
                entry = buckets[chainIndex];
                if ( kVal == entry.getKVal() ) {
                    return entry;
                }
            }
            entry = producer.apply(kVal);
            if ( entry != null ) {
                append(entry, bucketIndex, chainIndex);
            }
            return entry;
        }

        private void insert( final KMER entry, final int bucketIndex ) {
            if ( buckets[bucketIndex] != null ) evict(bucketIndex);
            buckets[bucketIndex] = entry;
            status[bucketIndex] = Byte.MIN_VALUE;
            size += 1;
        }

        private void append( final KMER entry, final int bucketIndex, final int endOfChainIndex ) {
            final int offsetToEndOfChain = getIndexDiff(bucketIndex, endOfChainIndex);

            // find an empty bucket for the new entry
            int emptyBucketIndex = findEmptyBucket(bucketIndex);

            // if the distance to the empty bucket is larger than this, we'll have to hopscotch
            final int maxOffset = offsetToEndOfChain + Byte.MAX_VALUE;

            // hopscotch the empty bucket into range if it's too far away
            int offsetToEmpty;
            while ( (offsetToEmpty = getIndexDiff(bucketIndex, emptyBucketIndex)) > maxOffset ) {
                emptyBucketIndex = hopscotch(bucketIndex, emptyBucketIndex);
            }

            // if the new entry lies downstream of the current chain end, just link it in
            if ( offsetToEmpty > offsetToEndOfChain ) {
                status[endOfChainIndex] += offsetToEmpty - offsetToEndOfChain;
            } else {
                linkIntoChain(bucketIndex, emptyBucketIndex);
            }
            buckets[emptyBucketIndex] = entry;
            size += 1;
        }

        private void evict( final int bucketToEvictIndex ) {
            final int bucketIndex = bucketForKVal(buckets[bucketToEvictIndex].getKVal());
            final int offsetToEvictee = getIndexDiff(bucketIndex, bucketToEvictIndex);
            int emptyBucketIndex = findEmptyBucket(bucketIndex);
            int fromIndex = bucketIndex;
            while ( true ) {
                while ( getIndexDiff(bucketIndex, emptyBucketIndex) > offsetToEvictee ) {
                    emptyBucketIndex = hopscotch(fromIndex, emptyBucketIndex);
                }
                if ( emptyBucketIndex == bucketToEvictIndex ) return;
                fromIndex = emptyBucketIndex;
                linkIntoChain(bucketIndex, emptyBucketIndex);
                int prevIndex = bucketIndex;
                int offsetToNext = getOffset(prevIndex);
                int nextIndex = getIndex(prevIndex, offsetToNext);
                while ( (offsetToNext = getOffset(nextIndex)) != 0 ) {
                    prevIndex = nextIndex;
                    nextIndex = getIndex(nextIndex, offsetToNext);
                }
                buckets[emptyBucketIndex] = buckets[nextIndex];
                buckets[nextIndex] = null;
                status[nextIndex] = 0;
                status[prevIndex] -= getOffset(prevIndex);
                emptyBucketIndex = nextIndex;
            }
        }

        private int bucketForKVal( final long kVal ) {
            int bucketIndex = (int)((kVal * SPREADER) % capacity);
            if ( bucketIndex < 0 ) {
                bucketIndex += capacity;
            }
            return bucketIndex;
        }

        private int findEmptyBucket( int bucketIndex ) {
            do {
                bucketIndex = getIndex(bucketIndex, 1);
            }
            while ( buckets[bucketIndex] != null );
            return bucketIndex;
        }

        // walk the chain until we find where the new slot gets linked in
        private void linkIntoChain( final int bucketIndex, final int emptyBucketIndex ) {
            int offsetToEmpty = getIndexDiff(bucketIndex, emptyBucketIndex);
            int tmpIndex = bucketIndex;
            int offset;
            while ( (offset = getOffset(tmpIndex)) < offsetToEmpty ) {
                tmpIndex = getIndex(tmpIndex, offset);
                offsetToEmpty -= offset;
            }
            offset -= offsetToEmpty;
            status[tmpIndex] -= offset;
            status[emptyBucketIndex] = (byte) offset;
        }

        private boolean isChainHead( final int bucketIndex ) {
            return (status[bucketIndex] & Byte.MIN_VALUE) != 0;
        }

        private int getOffset( final int bucketIndex ) {
            return status[bucketIndex] & Byte.MAX_VALUE;
        }

        private int getIndex( final int bucketIndex, final int offset ) {
            int result = bucketIndex + offset;
            if ( result >= capacity ) result -= capacity;
            else if ( result < 0 ) result += capacity;
            return result;
        }

        // bucket1 is assumed to be upstream of bucket2 (even if bucket2's index has wrapped)
        // i.e., the result is always positive
        private int getIndexDiff( final int bucketIndex1, final int bucketIndex2 ) {
            int result = bucketIndex2 - bucketIndex1;
            if ( result < 0 ) result += capacity;
            return result;
        }

        private int hopscotch( final int fromIndex, final int emptyBucketIndex ) {
            final int fromToEmptyDistance = getIndexDiff(fromIndex, emptyBucketIndex);
            int offsetToEmpty = Byte.MAX_VALUE;
            while ( offsetToEmpty > 1 ) {
                final int bucketIndex = getIndex(emptyBucketIndex, -offsetToEmpty);
                final int offsetInBucket = getOffset(bucketIndex);
                if ( offsetInBucket != 0 &&
                        offsetInBucket < offsetToEmpty &&
                        offsetToEmpty-offsetInBucket < fromToEmptyDistance ) {
                    final int bucketToMoveIndex = getIndex(bucketIndex, offsetInBucket);
                    move(bucketIndex, bucketToMoveIndex, emptyBucketIndex);
                    return bucketToMoveIndex;
                }
                offsetToEmpty -= 1;
            }
            // this happens now and then, but is usually caught and remedied by a resize
            throw new HopscotchException("Hopscotching failed at load factor "+(1.*size/capacity));
        }


        private void move( int predecessorBucketIndex, final int bucketToMoveIndex, final int emptyBucketIndex ) {
            int toEmptyDistance = getIndexDiff(bucketToMoveIndex, emptyBucketIndex);
            int nextOffset = getOffset(bucketToMoveIndex);
            if ( nextOffset == 0 || nextOffset > toEmptyDistance ) {
                status[predecessorBucketIndex] += toEmptyDistance;
            } else {
                status[predecessorBucketIndex] += nextOffset;
                toEmptyDistance -= nextOffset;
                predecessorBucketIndex = getIndex(bucketToMoveIndex, nextOffset);
                while ( (nextOffset = getOffset(predecessorBucketIndex)) != 0 && nextOffset < toEmptyDistance ) {
                    toEmptyDistance -= nextOffset;
                    predecessorBucketIndex = getIndex(predecessorBucketIndex, nextOffset);
                }
                status[predecessorBucketIndex] = (byte) toEmptyDistance;
            }
            if ( nextOffset != 0 ) {
                status[emptyBucketIndex] = (byte) (nextOffset - toEmptyDistance);
            }
            buckets[emptyBucketIndex] = buckets[bucketToMoveIndex];
            buckets[bucketToMoveIndex] = null;
            status[bucketToMoveIndex] = 0;
        }

        private void resize() {
            final int oldCapacity = capacity;
            final int oldSize = size;
            final KMER[] oldBuckets = buckets;
            final byte[] oldStatus = status;

            capacity = SetSizeUtils.getLegalSizeAbove(capacity);
            size = 0;
            buckets = makeBuckets(capacity);
            status = new byte[capacity];

            try {
                int idx = 0;
                do {
                    final KMER entry = oldBuckets[idx];
                    if ( entry != null ) add(entry);
                }
                while ( (idx = (idx+127)%oldCapacity) != 0 );
            } catch ( final IllegalStateException ise ) {
                capacity = oldCapacity;
                size = oldSize;
                buckets = oldBuckets;
                status = oldStatus;
                // this shouldn't happen except in the case of really bad hashCode implementations
                throw new IllegalStateException("Hopscotching failed at load factor "+1.*size/capacity+", and resizing didn't help.");
            }

            if ( size != oldSize ) {
                // this should never happen, period.
                throw new IllegalStateException("Lost some elements during resizing.");
            }
        }

        private void add( final KMER entry ) {
            final int bucketIndex = bucketForKVal(entry.getKVal());

            // if there's a squatter where the new entry should go, move it elsewhere and put the entry there
            if ( buckets[bucketIndex] != null && !isChainHead(bucketIndex) ) evict(bucketIndex);

            // if the place where it should go is empty, just put the new entry there
            if ( buckets[bucketIndex] == null ) {
                buckets[bucketIndex] = entry;
                status[bucketIndex] = Byte.MIN_VALUE;
                size += 1;
                return;
            }

            // walk to end of chain
            // along the way, make sure the entry isn't already present if necessary
            int endOfChainIndex = bucketIndex;
            int offset;
            while ( (offset = getOffset(endOfChainIndex)) != 0 ) {
                endOfChainIndex = getIndex(endOfChainIndex, offset);
            }

            append(entry, bucketIndex, endOfChainIndex);
        }

        @SuppressWarnings("unchecked")
        private KMER[] makeBuckets( final int size ) {
            return (KMER[])new Kmer[size];
        }

        private static int computeCapacity( final int size ) {
            if ( size < LOAD_FACTOR*Integer.MAX_VALUE ) {
                final int augmentedSize = (int) (size / LOAD_FACTOR);
                for ( final int legalSize : SetSizeUtils.legalSizes ) {
                    if ( legalSize >= augmentedSize ) return legalSize;
                }
            }
            return SetSizeUtils.legalSizes[SetSizeUtils.legalSizes.length - 1];
        }

        private static final class HopscotchException extends IllegalStateException {
            private static final long serialVersionUID = 1L;
            public HopscotchException( final String message ) { super(message); }
        }

        private final class Itr implements Iterator<KMER> {
            private int bucketsIndex = -1;
            private KMER next = null;

            public Itr() { advance(); }

            public boolean hasNext() { return next != null; }

            public KMER next() {
                final KMER result = next;
                if ( result == null ) throw new NoSuchElementException("iterator is exhausted");
                advance();
                return result;
            }

            private void advance() {
                next = null;
                while ( next == null && ++bucketsIndex < buckets.length ) {
                    next = buckets[bucketsIndex];
                }
            }
        }
    }

    public static final class KmerAdjacency extends Kmer {
        private KmerAdjacency solePredecessor = null; // set to null if there are no predecessors, or multiple predecessors
        private KmerAdjacency soleSuccessor = null; // set to null if there are no successors, or multiple successors
        private int predecessorMask = 0; // bit mask of observed kmers preceding this one
        private int successorMask = 0; // bit mask observed kmers following this one
        private Contig contig = null; // the contig that comprises this Kmer
        private int contigOffset;
        private final List<Integer> observations; // the reads in which this kmer was observed
        private final KmerAdjacency rc; // the reverse-complement of this kmer
        private static final int[] COUNT_FOR_MASK =
                //side sum for binary values from 0 -> 15
                //0000  0001 0010 0011 0100 0101 0110 0111 1000 1001 1010 1011 1100 1101 1110 1111
                {    0,    1,   1,   2,   1,   2,   2,   3,   1,   2,   2,   3,   2,   3,   3,   4 };

        public KmerAdjacency( final long kVal ) {
            super(kVal);
            observations = new ArrayList<>();
            this.rc = new KmerAdjacency(this);
        }

        // constructor for reverse-complement of existing KmerAdjacency
        private KmerAdjacency( final KmerAdjacency rc ) {
            super(reverseComplement(rc.getKVal()));
            this.observations = rc.observations;
            this.rc = rc;
        }

        public KmerAdjacency toCanonical() { return isCanonical() ? this : rc; }

        public KmerAdjacency getSolePredecessor() { return solePredecessor; } // may return null
        public int getPredecessorMask() { return predecessorMask; }
        public void removePredecessor( final int callToRemove, final KmerSet<KmerAdjacency> kmerAdjacencySet ) {
            predecessorMask &= ~(1 << callToRemove);
            rc.successorMask &= ~(1 << (3 - callToRemove));
            solePredecessor = null;
            rc.soleSuccessor = null;
            if ( getPredecessorCount() == 1 ) {
                for ( int call = 0; call != 4; ++call ) {
                    if ( ((1 << call) & predecessorMask) != 0 ) {
                        solePredecessor = find(getPredecessorVal(call), kmerAdjacencySet);
                        rc.soleSuccessor = solePredecessor.rc();
                        break;
                    }
                }
            }
        }
        public void clearPredecessorMask() { predecessorMask = 0; solePredecessor = null; }
        public int getPredecessorCount() { return COUNT_FOR_MASK[predecessorMask]; }
        public long getPredecessorVal( final int call ) { return (getKVal() >> 2) | ((long)call << (2 * (KSIZE - 1))); }

        public KmerAdjacency getSoleSuccessor() { return soleSuccessor; } // may return null
        public int getSuccessorMask() { return successorMask; }
        public void removeSuccessor( final int callToRemove, final KmerSet<KmerAdjacency> kmerAdjacencySet ) {
            successorMask &= ~(1 << callToRemove);
            rc.predecessorMask &= ~(1 << (3 - callToRemove));
            soleSuccessor = null;
            rc.solePredecessor = null;
            if ( getSuccessorCount() == 1 ) {
                for ( int call = 0; call != 4; ++call ) {
                    if ( ((1 << call) & successorMask) != 0 ) {
                        soleSuccessor = find(getSuccessorVal(call), kmerAdjacencySet);
                        rc.solePredecessor = soleSuccessor.rc();
                        break;
                    }
                }
            }
        }
        public void clearSuccessorMask() { successorMask = 0; soleSuccessor = null; }
        public int getSuccessorCount() { return COUNT_FOR_MASK[successorMask]; }
        public long getSuccessorVal( final int call ) { return ((getKVal() << 2) & KMASK) | call; }

        public Contig getContig() { return contig; }
        public int getContigOffset() { return contigOffset; }
        public int getNObservations() { return observations.size(); }
        public List<Integer> getObservations() { return observations; }
        public KmerAdjacency rc() { return rc; }

        public void observe( final KmerAdjacency predecessor, final KmerAdjacency successor, final int readId ) {
            if ( predecessor != null ) {
                final int initialCall = predecessor.getInitialCall();
                final int newPredecessorMask = 1 << initialCall;
                if ( (newPredecessorMask & predecessorMask) == 0 ) {
                    final int rcPredecessorMask = 1 << (3 - initialCall);
                    if ( predecessorMask != 0 ) {
                        solePredecessor = null;
                        predecessorMask |= newPredecessorMask;
                        rc.soleSuccessor = null;
                        rc.successorMask |= rcPredecessorMask;
                    } else {
                        solePredecessor = predecessor;
                        rc.soleSuccessor = predecessor.rc();
                        predecessorMask = newPredecessorMask;
                        rc.successorMask = rcPredecessorMask;
                    }
                }
            }
            if ( successor != null ) {
                final int finalCall = successor.getFinalCall();
                final int newSuccessorMask = 1 << finalCall;
                if ( (newSuccessorMask & successorMask) == 0 ) {
                    final int rcSuccessorMask = 1 << (3 - finalCall);
                    if ( successorMask != 0 ) {
                        soleSuccessor = null;
                        successorMask |= newSuccessorMask;
                        rc.solePredecessor = null;
                        rc.predecessorMask |= rcSuccessorMask;
                    } else {
                        soleSuccessor = successor;
                        rc.solePredecessor = successor.rc();
                        successorMask = newSuccessorMask;
                        rc.predecessorMask = rcSuccessorMask;
                    }
                }
            }
            observations.add(readId);
        }

        public void setContig( final Contig contig, final int contigOffset ) {
            this.contig = contig;
            this.contigOffset = contigOffset;
            if ( contig == null ) {
                rc.contig = null;
                rc.contigOffset = 0;
            } else {
                rc.contig = contig.rc();
                rc.contigOffset = contig.size() - KSIZE - contigOffset;
            }
            rc.contig = contig == null ? null : contig.rc();
        }

        @Override public String toString() {
            final StringBuilder sb = new StringBuilder(KSIZE);
            long currentVal = getKVal();
            for ( int idx = 0; idx != KSIZE; ++idx ) {
                sb.append("ACGT".charAt((int)currentVal & 3));
                currentVal >>= 2;
            }
            sb.reverse();
            return sb.toString();
        }

        public static void kmerize( final byte[] calls, final byte[] quals, final int readId,
                                    final byte qMin, final KmerSet<KmerAdjacency> kmerSet ) {
            int currentCount = 0;
            long currentKVal = 0;
            KmerAdjacency prevAdjacency = null;
            KmerAdjacency currentAdjacency = null;
            for ( int idx = 0; idx != calls.length; ++idx ) {
                if ( quals[idx] <  qMin ) {
                    if ( currentAdjacency != null ) {
                        currentAdjacency.observe(prevAdjacency, null, readId);
                    }
                    currentCount = 0;
                    currentAdjacency = prevAdjacency = null;
                    continue;
                }
                currentKVal <<= 2;
                switch ( calls[idx] ) {
                    case 'A': case 'a': break;
                    case 'C': case 'c': currentKVal += 1; break;
                    case 'G': case 'g': currentKVal += 2; break;
                    case 'T': case 't': currentKVal += 3; break;
                    default:
                        if ( currentAdjacency != null ) {
                            currentAdjacency.observe(prevAdjacency, null, readId);
                        }
                        currentCount = 0; currentAdjacency = prevAdjacency = null; continue;
                }
                if ( ++currentCount >= KSIZE ) {
                    final long nextKVal = currentKVal & KMASK;
                    final KmerAdjacency nextAdjacency =
                            isCanonical(nextKVal) ?
                                    kmerSet.findOrAdd(nextKVal, KmerAdjacency::new) :
                                    kmerSet.findOrAdd(reverseComplement(nextKVal), KmerAdjacency::new).rc();
                    if ( currentAdjacency != null ) {
                        currentAdjacency.observe(prevAdjacency, nextAdjacency, readId);
                    }
                    prevAdjacency = currentAdjacency;
                    currentAdjacency = nextAdjacency;
                }
            }
            if ( currentAdjacency != null ) {
                currentAdjacency.observe(prevAdjacency, null, readId);
            }
        }

        private static KmerAdjacency find( final long kVal, final KmerSet<KmerAdjacency> kmerAdjacencySet ) {
            if ( isCanonical(kVal) ) return kmerAdjacencySet.find(kVal);
            final KmerAdjacency result = kmerAdjacencySet.find(reverseComplement(kVal));
            return result == null ? result : result.rc();
        }

        private static long kVal( final CharSequence calls, final int start ) {
            long kVal = 0;
            final int end = start + KSIZE;
            int idx = start;
            while ( idx != end ) {
                kVal <<= 2;
                switch ( calls.charAt(idx++) ) {
                    case 'A': break;
                    case 'C': kVal += 1; break;
                    case 'G': kVal += 2; break;
                    case 'T': kVal += 3; break;
                }
            }
            return kVal;
        }

        // Lookup table for reverse-complementing each possible byte value.
        // Each pair of bits represents a base, so you have to reverse bits pairwise and then invert all bits.
        // This is most quickly and easily done with a lookup table.
        private static final long[] BYTEWISE_REVERSE_COMPLEMENT;
        static {
            BYTEWISE_REVERSE_COMPLEMENT = new long[256];
            for ( int bIn = 0; bIn != 256; ++bIn ) {
                BYTEWISE_REVERSE_COMPLEMENT[bIn] =
                        ~(((bIn & 3) << 6) | (((bIn >> 2) & 3) << 4) | (((bIn >> 4) & 3) << 2) | ((bIn >> 6) & 3)) & 0xffL;
            }
        }

        public static long reverseComplement( long val ) {
            // process val one byte at a time
            long result = BYTEWISE_REVERSE_COMPLEMENT[(int)val & 0xFF]; // handle the low-order byte
            int nBytes = 8;
            while ( --nBytes != 0 ) { // pre-decrementing:  we'll go through the loop 7 times
                // rotate down by a byte
                val >>= 8;
                // rotate up by a byte and OR in the reverse complement of the next byte
                result = (result << 8) | BYTEWISE_REVERSE_COMPLEMENT[(int)val & 0xFF];
            }
            return result >>> (Long.SIZE - 2*KSIZE);
        }
    }

    public enum ContigOrientation {
        FWD, // k-mer appears at the 5' end of the contig
        REV, // k-mer appears at the 5' end of the reverse-complemented contig
        BOTH // k-mer occurs on 5' end of the contig and its RC (can happen when the contig is a palindrome)
    }

    public static final class ContigEndKmer extends Kmer {
        private final Contig contig;
        private final ContigOrientation contigOrientation;

        public ContigEndKmer( final long kVal, final Contig contig, final ContigOrientation contigEnd ) {
            super(kVal);
            this.contig = contig;
            this.contigOrientation = contigEnd;
        }

        public Contig getContig() { return contig; }
        public ContigOrientation getContigOrientation() { return contigOrientation; }
    }

    public interface Contig {
        CharSequence getSequence();
        int getMaxObservations();
        KmerAdjacency getFirstKmer();
        KmerAdjacency getLastKmer();
        List<Contig> getPredecessors();
        List<Contig> getSuccessors();
        int getComponentId();
        int size();
        Contig rc();
        ContigImpl canonical();
    }

    public static final class ContigImpl implements Contig {
        private final CharSequence sequence;
        private final int maxObservations;
        private final KmerAdjacency firstKmer;
        private final KmerAdjacency lastKmer;
        private final List<Contig> predecessors;
        private final List<Contig> successors;
        private int componentId;
        private String name;
        private final Contig rc;

        public ContigImpl( final KmerAdjacency firstKmerAdjacency ) {
            final StringBuilder sb = new StringBuilder(firstKmerAdjacency.toString());
            int maxObservations = firstKmerAdjacency.getNObservations();
            KmerAdjacency lastKmerAdjacency = firstKmerAdjacency;
            for ( KmerAdjacency kmerAdjacency = firstKmerAdjacency.getSoleSuccessor();
                  kmerAdjacency != null;
                  kmerAdjacency = kmerAdjacency.getSoleSuccessor() ) {
                if ( firstKmerAdjacency == kmerAdjacency || kmerAdjacency.getPredecessorCount() != 1 ) break;
                sb.append("ACGT".charAt(kmerAdjacency.getFinalCall()));
                maxObservations = Math.max(maxObservations, kmerAdjacency.getNObservations());
                lastKmerAdjacency = kmerAdjacency;
            }
            this.sequence = sb.toString();
            this.maxObservations = maxObservations;
            this.firstKmer = firstKmerAdjacency;
            this.lastKmer = lastKmerAdjacency;
            this.predecessors = new ArrayList<>(firstKmer.getPredecessorCount());
            this.successors = new ArrayList<>(lastKmer.getSuccessorCount());
            this.rc = new ContigRCImpl(this);

            int offset = 0;
            for ( KmerAdjacency kmerAdjacency = firstKmerAdjacency;
                  kmerAdjacency != lastKmerAdjacency;
                  kmerAdjacency = kmerAdjacency.getSoleSuccessor() ) {
                kmerAdjacency.setContig(this, offset++);
            }
            lastKmerAdjacency.setContig(this, offset);
        }

        // create a new contig by joining two contigs
        public ContigImpl( final Contig predecessor, final Contig successor ) {
            final StringBuilder sb = new StringBuilder(predecessor.getSequence());
            final CharSequence successorSequence = successor.getSequence();
            sb.append(successorSequence.subSequence(KSIZE - 1, successorSequence.length()));
            this.sequence = sb.toString();
            this.maxObservations = Math.max(predecessor.getMaxObservations(), successor.getMaxObservations());
            this.firstKmer = predecessor.getFirstKmer();
            this.lastKmer = successor.getLastKmer();
            this.predecessors = new ArrayList<>(predecessor.getPredecessors());
            this.successors = new ArrayList<>(successor.getSuccessors());
            this.rc = new ContigRCImpl(this);
        }

        public void setComponentId( final int id ) { this.componentId = id; }
        public void setName( final int id ) { this.name = "tig" + id; }

        @Override public CharSequence getSequence() { return sequence; }
        @Override public int getMaxObservations() { return maxObservations; }
        @Override public KmerAdjacency getFirstKmer() { return firstKmer; }
        @Override public KmerAdjacency getLastKmer() { return lastKmer; }
        @Override public List<Contig> getPredecessors() { return predecessors; }
        @Override public List<Contig> getSuccessors() { return successors; }
        @Override public int getComponentId() { return componentId; }
        @Override public int size() { return sequence.length(); }
        @Override public Contig rc() { return rc; }
        @Override public ContigImpl canonical() { return this; }
        @Override public String toString() { return name; }
    }

    public static final class ContigRCImpl implements Contig {
        private final CharSequence sequence;
        private final List<Contig> predecessors;
        private final List<Contig> successors;
        private final ContigImpl rc;

        public ContigRCImpl( final ContigImpl contig ) {
            this.sequence = new SequenceRC(contig.getSequence());
            this.predecessors = new ListRC(contig.getSuccessors());
            this.successors = new ListRC(contig.getPredecessors());
            this.rc = contig;
        }

        @Override public CharSequence getSequence() { return sequence; }
        @Override public int getMaxObservations() { return rc.getMaxObservations(); }
        @Override public KmerAdjacency getFirstKmer() { return rc.getLastKmer().rc(); }
        @Override public KmerAdjacency getLastKmer() { return rc.getFirstKmer().rc(); }
        @Override public List<Contig> getPredecessors() { return predecessors; }
        @Override public List<Contig> getSuccessors() { return successors; }
        @Override public int getComponentId() { return rc.getComponentId(); }
        @Override public int size() { return sequence.length(); }
        @Override public Contig rc() { return rc; }
        @Override public ContigImpl canonical() { return rc; }
        @Override public String toString() { return rc.toString()+"RC"; }

        public static final class SequenceRC implements CharSequence {
            private final int lenLess1;
            private final CharSequence sequence;

            public SequenceRC( final CharSequence sequence ) {
                this.lenLess1 = sequence.length() - 1;
                this.sequence = sequence;
            }

            @Override public int length() { return sequence.length(); }
            @Override public char charAt( final int index ) {
                final char result;
                switch ( sequence.charAt(lenLess1 - index) ) {
                    case 'A': result = 'T'; break;
                    case 'C': result = 'G'; break;
                    case 'G': result = 'C'; break;
                    case 'T': result = 'A'; break;
                    default: result = 'N'; break;
                }
                return result;
            }
            @Override public CharSequence subSequence( final int start, final int end ) {
                return new StringBuilder(end - start).append(this, start, end);
            }
            @Override public String toString() { return new StringBuilder(this).toString(); }
        }

        public static final class ListRC extends AbstractList<Contig> {
            private final List<Contig> contigList;

            public ListRC( final List<Contig> contigList ) {
                this.contigList = contigList;
            }

            @Override public Contig get( final int index ) { return contigList.get(index).rc(); }
            @Override public int size() { return contigList.size(); }
            @Override public Contig set( final int index, final Contig contig ) {
                return contigList.set(index, contig.rc()).rc();
            }
            @Override public void add( final int index, final Contig contig ) { contigList.add(index, contig.rc()); }
            @Override public Contig remove( final int index ) { return contigList.remove(index).rc(); }
        }
    }

    public static final class PathPart {
        public static String NO_KMER = "NoKmer";
        public static String X_CONTIG = "XContig";
        private final String contigName;
        private final int maxOffset;
        private final int start;
        private int stop;

        public PathPart( final String contigName, final int contigLength, final int start ) {
            this.contigName = contigName;
            this.maxOffset = contigLength - KSIZE + 1;
            this.start = start;
            this.stop = start + 1;
        }

        public String getContigName() { return contigName; }
        public void extendPath() { stop += 1; }
        public String toString() {
            if ( contigName == NO_KMER || contigName == X_CONTIG ) {
                return contigName + "(" + stop + ")";
            }
            return contigName + "(" + start + "," + stop + "/" + maxOffset + ")";
        }
    }
}
