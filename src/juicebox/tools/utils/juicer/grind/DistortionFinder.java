/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2011-2019 Broad Institute, Aiden Lab
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package juicebox.tools.utils.juicer.grind;

import juicebox.data.*;
import juicebox.mapcolorui.Feature2DHandler;
import juicebox.tools.utils.dev.drink.ExtractingOEDataUtils;
import juicebox.track.feature.Feature2D;
import juicebox.track.feature.Feature2DList;
import juicebox.windowui.HiCZoom;
import juicebox.windowui.NormalizationType;
import org.apache.commons.math.linear.RealMatrix;
import org.broad.igv.feature.Chromosome;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

public class DistortionFinder implements RegionFinder {


    private Integer sliceRowSize;
    private Integer sliceColSize;
    private Integer numExamples;
    private Dataset ds;
    private Feature2DList features;
    private String path;
    private ChromosomeHandler chromosomeHandler;
    private NormalizationType norm;
    private boolean useObservedOverExpected;
    private boolean useDenseLabels;
    private Set<Integer> resolutions;
    private int cornerOffBy;
    private int stride;

    public DistortionFinder(int sliceRowSize, int sliceColSize, int numExamples, Dataset ds, Feature2DList features, File outputDirectory, ChromosomeHandler chromosomeHandler, NormalizationType norm,
                            boolean useObservedOverExpected, boolean useDenseLabels, Set<Integer> resolutions, int corner_off_by, int stride) {

        this.sliceRowSize = sliceRowSize;
        this.sliceColSize = sliceColSize;
        this.numExamples = numExamples;
        this.ds = ds;
        this.features = features;
        this.path = outputDirectory.getPath();
        this.chromosomeHandler = chromosomeHandler;
        this.norm = norm;
        this.useObservedOverExpected = useObservedOverExpected;
        this.useDenseLabels = useDenseLabels;
        this.resolutions = resolutions;
        this.cornerOffBy = corner_off_by;
        this.stride = stride;
    }

    private void makeDir(String path) {
        File file = new File(path);
        if (!file.isDirectory()) {
            file.mkdir();
        }
    }

    @Override
    public void makePositiveExamples() {

        final String negPath = path + "/negative";
        final String posPath = path + "/positive";
        makeDir(negPath);
        makeDir(posPath);

        try {

            final Writer posWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path + "/pos_file_names.txt"), StandardCharsets.UTF_8));
            final Writer negWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path + "/neg_file_names.txt"), StandardCharsets.UTF_8));
            final Writer posLabelWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path + "/pos_label_file_names.txt"), StandardCharsets.UTF_8));

            final Feature2DHandler feature2DHandler = new Feature2DHandler(features);

            for (int resolution : resolutions) {
                for (Chromosome chrom : chromosomeHandler.getChromosomeArrayWithoutAllByAll()) {
                    Matrix matrix = ds.getMatrix(chrom, chrom);
                    if (matrix == null) continue;
                    HiCZoom zoom = ds.getZoomForBPResolution(resolution);
                    final MatrixZoomData zd = matrix.getZoomData(zoom);
                    if (zd == null) continue;
                    System.out.println("Currently processing: " + chrom.getName());

                    // sliding along the diagonal
                    for (int rowIndex = 0; rowIndex < (chrom.getLength() / resolution) - sliceColSize; rowIndex += stride) {
                        int startCol = Math.max(0, rowIndex - cornerOffBy);
                        int endCol = Math.min(rowIndex + cornerOffBy, (chrom.getLength() / resolution) - sliceColSize);
                        for (int colIndex = startCol; colIndex < endCol; colIndex += stride) {
                            getTrainingDataAndSaveToFile(zd, chrom, rowIndex, colIndex, resolution, feature2DHandler, sliceRowSize, sliceColSize,
                                    posPath, negPath, posWriter, posLabelWriter, negWriter, false);
                        }
                    }
                    for (int rowIndex = sliceColSize; rowIndex < (chrom.getLength() / resolution); rowIndex += stride) {
                        int startCol = Math.max(sliceColSize, rowIndex - cornerOffBy);
                        int endCol = Math.min(rowIndex + cornerOffBy, (chrom.getLength() / resolution));
                        for (int colIndex = startCol; colIndex < endCol; colIndex += stride) {
                            getTrainingDataAndSaveToFile(zd, chrom, rowIndex, colIndex, resolution, feature2DHandler, sliceRowSize, sliceColSize,
                                    posPath, negPath, posWriter, posLabelWriter, negWriter, true);
                        }
                    }
                }
            }
            posWriter.close();
            negWriter.close();
            posLabelWriter.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void getTrainingDataAndSaveToFile(MatrixZoomData zd, Chromosome chrom, int rowIndex, int colIndex, int resolution,
                                              Feature2DHandler feature2DHandler, Integer x, Integer y, String posPath, String negPath,
                                              Writer posWriter, Writer posLabelWriter, Writer negWriter, boolean isVerticalStripe) throws IOException {

        int rectULX = rowIndex;
        int rectULY = colIndex;
        int rectLRX = rowIndex + x;
        int rectLRY = colIndex + y;
        int numRows = x;
        int numCols = y;


        if (isVerticalStripe) {
            rectULX = rowIndex - y;
            rectULY = colIndex - x;
            rectLRX = rowIndex;
            rectLRY = colIndex;
            numRows = y;
            numCols = x;
        }

        RealMatrix localizedRegionData;
        if (useObservedOverExpected) {
            ExpectedValueFunction df = ds.getExpectedValues(zd.getZoom(), norm);
            if (df == null) {
                System.err.println("O/E data not available at " + zd.getZoom() + " " + norm);
                return;
            }
            localizedRegionData = ExtractingOEDataUtils.extractLocalThresholdedLogOEBoundedRegion(zd, rectULX, rectLRX,
                    rectULY, rectLRY, numRows, numCols, norm, true, df, chrom.getIndex(), 2, true);
        } else {
            localizedRegionData = HiCFileTools.extractLocalBoundedRegion(zd,
                    rectULX, rectLRX, rectULY, rectLRY, numRows, numCols, norm, true);
        }

        net.sf.jsi.Rectangle currentWindow = new net.sf.jsi.Rectangle(rectULX * resolution,
                rectULY * resolution, rectLRX * resolution, rectLRY * resolution);

        List<Feature2D> inputListFoundFeatures = feature2DHandler.getContainedFeatures(chrom.getIndex(), chrom.getIndex(),
                currentWindow);

        boolean stripeIsFound = false;

        double[][] labelsMatrix = new double[numRows][numCols];
        for (Feature2D feature2D : inputListFoundFeatures) {
            int rowLength = Math.max((feature2D.getEnd1() - feature2D.getStart1()) / resolution, 1);
            int colLength = Math.max((feature2D.getEnd2() - feature2D.getStart2()) / resolution, 1);


            int startRowOf1 = feature2D.getStart1() / resolution - rectULX;
            int startColOf1 = feature2D.getStart2() / resolution - rectULY;
            for (int i = 0; i < Math.min(rowLength, numRows); i++) {
                for (int j = 0; j < Math.min(colLength, numCols); j++) {
                    labelsMatrix[startRowOf1 + i][startColOf1 + j] = 1.0;
                }
            }
            stripeIsFound = true;

        }


        //  MatrixTools.saveMatrixDataToFile(chrom, rowIndex, colIndex, "_matrix.txt", negPath, localizedRegionData.getData(), negWriter, isVerticalStripe);

    }

    private void fillInAreaUnderDiagonal(RealMatrix localizedRegionData, boolean isVerticalStripe) {
        if (isVerticalStripe) {
            int numRows = localizedRegionData.getRowDimension();
            int numCols = localizedRegionData.getColumnDimension();
            int diagonalULIndex = numRows - numCols;
            for (int i = diagonalULIndex; i < numRows; i++) {
                for (int j = 0; j < (i - diagonalULIndex); j++) {
                    localizedRegionData.setEntry(i, j, localizedRegionData.getEntry(diagonalULIndex + j, i - diagonalULIndex));
                }
            }
        } else {
            for (int i = 0; i < localizedRegionData.getRowDimension(); i++) {
                for (int j = 0; j < i; j++) {
                    localizedRegionData.setEntry(i, j, localizedRegionData.getEntry(j, i));
                }
            }
        }
    }

    @Override
    public void makeNegativeExamples() {

    }
}