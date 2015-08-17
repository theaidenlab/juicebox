/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2011-2015 Broad Institute, Aiden Lab
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

package juicebox.tools.utils.juicer.arrowhead;

import com.google.common.collect.Lists;
import com.google.common.primitives.Doubles;
import juicebox.tools.utils.common.MatrixTools;
import org.apache.commons.math.linear.RealMatrix;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Created by muhammadsaadshamim on 6/5/15.
 */
public class BlockResults {

    private final ArrowheadScoreList internalList;
    private final ArrowheadScoreList internalControl;
    private List<HighScore> results = new ArrayList<HighScore>();

    public BlockResults(RealMatrix observed, double varThreshold, double signThreshold, int increment,
                        ArrowheadScoreList list, ArrowheadScoreList control) {

        internalList = list.deepCopy();
        internalControl = control.deepCopy();

        int n = Math.min(observed.getRowDimension(), observed.getColumnDimension());
        int gap = 7;

        RealMatrix dUpstream = calculateDirectionalityIndexUpstream(observed, n, gap);
        MatrixTriangles triangles = new MatrixTriangles(dUpstream);

        triangles.generateBlockScoreCalculations();
        triangles.updateScoresUsingList(internalList);
        triangles.updateScoresUsingList(internalControl);
        triangles.thresholdScoreValues(varThreshold, signThreshold, increment);

        List<Set<Point>> connectedComponents = triangles.extractConnectedComponents();
        //System.out.println("CC "+connectedComponents.size());

        results = triangles.calculateResults(connectedComponents);
        plotArrowheadFigures();
    }

    /**
     * TODO
     */
    private void plotArrowheadFigures() {


        // TODO
    }

    /**
     * calculate D upstream, directionality index upstream
     *
     * @param observed
     * @param n
     * @param gap
     * @return dUpstream
     */
    private RealMatrix calculateDirectionalityIndexUpstream(RealMatrix observed, int n, int gap) {

        RealMatrix dUpstream = MatrixTools.cleanArray2DMatrix(n);

        for (int i = 0; i < n; i++) {
            int window = Math.min(n - i - gap, i - gap);
            window = Math.min(window, n);

            // TODO MSS Arrowhead fix window bug after MATLAB testing done
            if (window >= gap) {
                double[] row = observed.getRow(i);

                // in MATLAB second index inclusive, but for java need +1
                double[] A = Doubles.toArray(Lists.reverse(Doubles.asList(Arrays.copyOfRange(row, i - window, i - gap + 1))));
                double[] B = Arrays.copyOfRange(row, i + gap, i + window + 1);

                double[] preference = new double[A.length];
                for (int j = 0; j < A.length; j++) {
                    preference[j] = (A[j] - B[j]) / (A[j] + B[j]);
                }

                int index = 0;
                for (int j = i + gap; j < i + window + 1; j++) {
                    dUpstream.setEntry(i, j, preference[index]);
                    index++;
                }
            }
        }

        return dUpstream;
    }

    /**
     * @return block results
     */
    public List<HighScore> getResults() {
        return results;
    }

    public ArrowheadScoreList getInternalList() {
        return internalList;
    }

    public ArrowheadScoreList getInternalControl() {
        return internalControl;
    }

    public void offsetResultsIndex(int offset) {
        for (HighScore score : results) {
            score.offsetIndex(offset);
        }
    }
}