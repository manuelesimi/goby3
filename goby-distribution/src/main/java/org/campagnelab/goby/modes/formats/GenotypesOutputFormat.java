/*
 * Copyright (C) 2009-2011 Institute for Computational Biomedicine,
 *                    Weill Medical College of Cornell University
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.campagnelab.goby.modes.formats;

import org.campagnelab.goby.modes.dsv.DiscoverVariantIterateSortedAlignments;
import org.campagnelab.goby.modes.dsv.DiscoverVariantPositionData;
import org.campagnelab.goby.modes.dsv.SampleCountInfo;
import org.campagnelab.goby.modes.DiscoverSequenceVariantsMode;
import org.campagnelab.goby.readers.vcf.ColumnType;
import org.campagnelab.goby.reads.RandomAccessSequenceInterface;
import org.campagnelab.goby.stats.VCFWriter;
import org.campagnelab.goby.util.OutputInfo;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.lang.MutableString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * @author Fabien Campagne
 *         Date: Mar 21, 2011
 *         Time: 2:37:43 PM
 */
public class GenotypesOutputFormat implements SequenceVariationOutputFormat {

    private int positionColumnIndex;
    private int numberOfGroups;
    private int numberOfSamples;
    private int[] refCountsPerSample;
    private int[] variantsCountPerSample;
    protected VCFWriter statsWriter;
    String[] samples;
    private int chromosomeColumnIndex;
    private int idColumnIndex;
    protected int biomartFieldIndex;

    public int getGenotypeFieldIndex() {
        return genotypeFieldIndex;
    }

    private int genotypeFieldIndex;

    public int getBaseCountFieldIndex() {
        return baseCountFieldIndex;
    }

    public int baseCountFieldIndex;
    private int zygFieldIndex;
    private int altCountsIndex;
    private int arrayCountsIndex;

    public int getFailBaseCountFieldIndex() {
        return failBaseCountFieldIndex;
    }

    private int failBaseCountFieldIndex;

    public int getGoodBaseCountFieldIndex() {
        return goodBaseCountFieldIndex;
    }

    private int goodBaseCountFieldIndex;
    private String[] singleton = new String[1];
    private int indelFlagFieldIndex = -1;
    private boolean siteObserved;


    //only for internal use
    private boolean ALT_FORMAT = false;


    public void defineColumns(OutputInfo writer, DiscoverSequenceVariantsMode mode) {
        samples = mode.getSamples();
        this.statsWriter = new VCFWriter(writer.getPrintWriter());
        biomartFieldIndex = statsWriter.defineField("INFO", "BIOMART_COORDS", 1, ColumnType.String, "Coordinates for use with Biomart.","biomart");
        defineInfoFields(statsWriter);
        defineGenotypeField(statsWriter);

        if (ALT_FORMAT){
            altCountsIndex = statsWriter.defineField("FORMAT", "AltCounts", 1, ColumnType.String, "AltCounts","altcounts");
            arrayCountsIndex = statsWriter.defineField("FORMAT", "ArrayCounts", 1, ColumnType.String, "ArrayCounts","arraycounts");
        }
        zygFieldIndex = statsWriter.defineField("FORMAT", "Zygosity", 1, ColumnType.String, "Zygosity","zygosity");
        statsWriter.defineSamples(samples);
        statsWriter.writeHeader();


    }

    public void defineInfoFields(VCFWriter statsWriter) {
        indelFlagFieldIndex = statsWriter.defineField("INFO", "INDEL", 1, ColumnType.Flag, "Indicates that the variation is an indel.","indel");

    }

    public void defineGenotypeField(VCFWriter statsWriter) {
        genotypeFieldIndex = statsWriter.defineField("FORMAT", "GT", 1, ColumnType.String, "Genotype","genotype");
        baseCountFieldIndex = statsWriter.defineField("FORMAT", "BC", 5, ColumnType.String, "Base counts in format A=?;T=?;C=?;G=?;N=?.","base-calls");
        goodBaseCountFieldIndex = statsWriter.defineField("FORMAT", "GB", 1, ColumnType.String, "Number of bases that pass base filters in this sample, or ignore string.","good-bases");
        failBaseCountFieldIndex = statsWriter.defineField("FORMAT", "FB", 1, ColumnType.String, "Number of bases that failed base filters in this sample, or ignore string.","failed-bases");
    }

    public void allocateStorage(int numberOfSamples, int numberOfGroups) {
        this.numberOfGroups = numberOfGroups;
        this.numberOfSamples = numberOfSamples;

        refCountsPerSample = new int[numberOfSamples];
        variantsCountPerSample = new int[numberOfSamples];
    }

    IntArrayList decreasingCounts = new IntArrayList();
    ObjectArraySet<String> sampleAlleleSet = new ObjectArraySet<String>();
    ObjectArraySet<String> alleleSet = new ObjectArraySet<String>();
    MutableString genotypeBuffer = new MutableString();

    @Override
    public void writeRecord(final DiscoverVariantIterateSortedAlignments iterator, final SampleCountInfo[] sampleCounts,
                            final int referenceIndex, int position, final DiscoverVariantPositionData list,
                            final int groupIndexA, final int groupIndexB) {

        position = position + 1; // report  1-based position
        fillVariantCountArrays(sampleCounts);

        CharSequence currentReferenceId = iterator.getReferenceId(referenceIndex);

        statsWriter.setId(".");
        statsWriter.setInfo(biomartFieldIndex,
                String.format("%s:%d:%d", currentReferenceId, position,
                        position));
        statsWriter.setChromosome(currentReferenceId);

        statsWriter.setPosition(position);
        /*    //   int location = 8930385;
       int location = 8930369;
       if (position == location || position - 1 == location || position + 1 == location) {
           System.out.println("STOP");
       } */
        writeGenotypes(statsWriter, sampleCounts, position);

        writeZygozity(sampleCounts);
        if (!alleleSet.isEmpty()) {
            // Do not write record if alleleSet is empty, IGV VCF track cannot handle that.
            statsWriter.writeRecord();
        }

        if (ALT_FORMAT){
            writeAltCounts(sampleCounts);
            writeCountsArray(sampleCounts);
        }
    }

    protected void writeAltCounts(SampleCountInfo[] sampleCounts) {
        for (int sampleIndex = 0; sampleIndex < numberOfSamples; sampleIndex++) {
            SampleCountInfo sci = sampleCounts[sampleIndex];
            String alt = sci.toString().replace("\n","");
            statsWriter.setSampleValue(altCountsIndex, sampleIndex, alt);
        }
    }


    protected void writeCountsArray(SampleCountInfo[] sampleCounts) {
        for (int sampleIndex = 0; sampleIndex < numberOfSamples; sampleIndex++) {
            SampleCountInfo sci = sampleCounts[sampleIndex];
            int numCounts = sci.getGenotypeMaxIndex();
            int[] counts = new int[numCounts];
            for (int genotypeIndex = 0; genotypeIndex < numCounts; genotypeIndex++) {
                counts[genotypeIndex] += sci.getGenotypeCount(genotypeIndex, true) + sci.getGenotypeCount(genotypeIndex, false);
            }
            statsWriter.setSampleValue(arrayCountsIndex, sampleIndex, Arrays.toString(counts));
        }
    }

    protected void writeZygozity(SampleCountInfo[] sampleCounts) {
        for (int sampleIndex = 0; sampleIndex < numberOfSamples; sampleIndex++) {
            SampleCountInfo sci = sampleCounts[sampleIndex];
            int alleleCount = 0;
            for (int genotypeIndex = 0; genotypeIndex < sci.getGenotypeMaxIndex(); ++genotypeIndex) {
                final int count = sci.getGenotypeCount(genotypeIndex);
                if (count > 0) {
                    ++alleleCount;
                }
            }
            String zygozity;
            switch (alleleCount) {
                case 0:
                    zygozity = "not-typed";
                    break;
                case 1:
                    zygozity = "homozygous";
                    break;
                case 2:
                    zygozity = "heterozygous";
                    break;
                default:

                    zygozity = "Mixture";
                    break;
            }

            statsWriter.setSampleValue(zygFieldIndex, sampleIndex, zygozity);

        }
    }

    ObjectArraySet<String> referenceSet = new ObjectArraySet<String>();

    public void writeGenotypes(VCFWriter statsWriter, SampleCountInfo[] sampleCounts, int position) {
        boolean referenceAlleleSetForIndel = false;
        siteObserved = false;
        boolean siteHasIndel = false;
        referenceSet.clear();
        statsWriter.clearAlternateAlleles();
        // clear the cross-sample allele set, which is used to determine if the VCF position should be written.
        alleleSet.clear();
        for (int sampleIndex = 0; sampleIndex < numberOfSamples; sampleIndex++) {

            sampleAlleleSet.clear();
            SampleCountInfo sci = sampleCounts[sampleIndex];
            int totalCount = 0;
            for (int genotypeIndex = 0; genotypeIndex < sci.getGenotypeMaxIndex(); ++genotypeIndex) {
                final int sampleCount = sci.getGenotypeCount(genotypeIndex);
                totalCount += sampleCount;
            }
            //  System.out.printf("totalCount %d failedCount %d%n",totalCount,sci.failedCount);
            statsWriter.setSampleValue(goodBaseCountFieldIndex, sampleIndex, totalCount);
            statsWriter.setSampleValue(failBaseCountFieldIndex, sampleIndex, sci.failedCount);

            int baseIndex = 0;
            int altGenotypeCount = 0;
            genotypeBuffer.setLength(0);

            final MutableString baseCountString = new MutableString();

            for (int genotypeIndex = 0; genotypeIndex < sci.getGenotypeMaxIndex(); ++genotypeIndex) {
                final int sampleCount = sci.getGenotypeCount(genotypeIndex);
                String genotype = sci.getGenotypeString(genotypeIndex);

                if (sampleCount > 0 && genotypeIndex != SampleCountInfo.BASE_OTHER_INDEX) {
                    siteObserved = true;

                    if (sci.isIndel(genotypeIndex)) {
                        siteHasIndel = true;
                    }
                    if (!sci.isReferenceGenotype(genotypeIndex)) {
                        statsWriter.addAlternateAllele(genotype);
                        altGenotypeCount++;
                        updateReferenceSet(sci.getReferenceGenotype());
                    } else {
                        //updateReferenceSet(genotype);
                        if (sci.isIndel(genotypeIndex)) {
                            siteHasIndel = true;
                            genotype = sci.getReferenceGenotype();

                        }
                        updateReferenceSet(genotype);

                    }
                    alleleSet.add(genotype);
                    sampleAlleleSet.add(genotype);
                    genotypeBuffer.append(genotype);
                    genotypeBuffer.append('/');

                }
                if (sampleCount > 0) {
                    baseCountString.append(genotype);
                    baseCountString.append('=');
                    baseCountString.append(Integer.toString(sampleCount));
                    baseCountString.append(',');
                }

            }

            if (sampleAlleleSet.size() == 1) {
                // when homozygous genotype 0/ write 0/0/ (last / will be removed when length is adjusted)
                genotypeBuffer.append(genotypeBuffer.copy());
            }
            if (baseCountString.length() >= 1) {
                baseCountString.setLength(baseCountString.length() - 1);
            }
            statsWriter.setSampleValue(baseCountFieldIndex, sampleIndex, baseCountString);

            if (siteObserved) {

                if (genotypeBuffer.length() > 1) {
                    // trim the trailing coma:
                    genotypeBuffer.setLength(genotypeBuffer.length() - 1);
                }
                if (referenceSet.size() <= 1) {
                    if (referenceSet.isEmpty()) {
                        statsWriter.setReferenceAllele(sci.getReferenceGenotype());
                    } else {
                        statsWriter.setReferenceAllele(referenceSet.toArray(singleton)[0]);
                    }

                } else {
                    LOG.error(String.format("Observed multiple indel references at position %d: \n %s %n",
                            position,
                            referenceSet));
                    // we use the longest reference sequence
                    int maxLength = 0;
                    for (final String ref : referenceSet) {
                        if (ref.length() > maxLength) {
                            statsWriter.setReferenceAllele(ref);
                            maxLength = ref.length();
                        }
                    }
                }
                statsWriter.setSampleValue(genotypeFieldIndex, sampleIndex, statsWriter.codeGenotype(genotypeBuffer.toString()));

            } else {


                statsWriter.setSampleValue(genotypeFieldIndex, sampleIndex, "./.");
            }


        }

        if (indelFlagFieldIndex != -1) {    // set indel flag only when the field is defined (i.e., client has called setInfoFields)
            statsWriter.setFlag(indelFlagFieldIndex, siteHasIndel);
        }
    }

    /**
     * Determine if the candidate reference genotype is new, and keep only those genotypes not already described by
     * longer genotypes.
     *
     * @param genotype
     */
    private void updateReferenceSet(String genotype) {
        if (!isIncludedIn(genotype, referenceSet)) {
            referenceSet.add(genotype);
        }

        for (final String refGenotype : referenceSet) {
            if (refGenotype != null && !genotype.equals(refGenotype) && isIncludedIn(refGenotype, genotype)) {
                referenceSet.remove(refGenotype);
                alleleSet.remove(refGenotype);
                sampleAlleleSet.remove(refGenotype);
            }
        }
    }

    private boolean isIncludedIn(String genotype, ObjectArraySet<String> referenceSet) {
        for (String g : referenceSet) {
            if (isIncludedIn(genotype, g)) return true;
        }
        return false;
    }

    /**
     * Returns true if genotype a is included in b.
     *
     * @param a
     * @param b
     * @return
     */
    private boolean isIncludedIn(String a, String b) {
        return b.startsWith(a);
    }


    public void close() {
        statsWriter.close();
    }

    @Override
    public void setGenome(RandomAccessSequenceInterface genome) {

    }

    @Override
    public void setGenomeReferenceIndex(int index) {

    }


    protected void fillVariantCountArrays
            (SampleCountInfo[] sampleCounts) {


        for (SampleCountInfo csi : sampleCounts) {
            final int sampleIndex = csi.sampleIndex;
            variantsCountPerSample[sampleIndex] = csi.varCount;
            refCountsPerSample[sampleIndex] = csi.refCount;
        }

    }

    /**
     * Used to log debug and informational messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(GenotypesOutputFormat.class);


}
