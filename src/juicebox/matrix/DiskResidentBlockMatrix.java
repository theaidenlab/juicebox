package juicebox.matrix;

import org.apache.log4j.Logger;
import org.broad.igv.util.ObjectCache;
import org.broad.igv.util.ParsingUtils;
import htsjdk.tribble.util.LittleEndianInputStream;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.seekablestream.SeekableStreamFactory;

import java.io.*;

/**
 * Matrix class backed by a file layed out in "block" format.
 * <p/>
 * File format:
 * Field	Type	Description
 * Magic number	Integer	Value should be 6515048, which when read as a zero delimited string is “hic”.
 * Version	Integer	The version number, currently =  1
 * Genome	String	The genome ID (e.g. “hg19”)
 * Chromosome 1	String	Name of first chromosome
 * Chromosome 2	String	Name of second chromosome
 * Bin size	Integer	The bin size in base-pairs
 * Lower bounds for scale	Float	5th percentile suggested
 * Upper bounds for scale	Float	95th percentile suggested
 * Number of rows	Integer	Number of rows in the matrix.  Rows correspond to chromosome 1.
 * Number of columns	Integer
 * Block size  Integer
 *
 * @author jrobinso
 *         Date: 8/8/12
 *         Time: 8:42 AM
 */
public class DiskResidentBlockMatrix implements BasicMatrix {

    private static Logger log = Logger.getLogger(DiskResidentBlockMatrix.class);

    String path;
    private String genome;
    private String chr1;
    private String chr2;
    private int binSize;
    float lowerValue;
    float upperValue;
    int dim;
    int blockSize;
    int remSize;   // Dimension of last block

    int arrayStartPosition;
    boolean isLoading = false;

    ObjectCache<String, float[][]> blockDataCache = new ObjectCache<String, float[][]>(200);
    private int nFullBlocks;

    public DiskResidentBlockMatrix(String path) throws IOException {
        this.path = path;
        init();
    }

    public String getChr1() {
        return chr1;
    }

    @Override
    public float getEntry(int row, int col) {

        int blockRowIdx = row / blockSize;
        int blockColIdx = col / blockSize;
        String key = "row" + blockRowIdx + "_col" + blockColIdx;
        float[][] blockData = blockDataCache.get(key);
        if (blockData == null) {
            blockData = loadBlockData(blockRowIdx, blockColIdx);
            blockDataCache.put(key, blockData);
        }

        if (blockData == null) {
            return Float.NaN;
        } else {
            int rowRelative = row - blockRowIdx * blockSize;
            int colRelative = col - blockColIdx * blockSize;
            return blockData[rowRelative][colRelative];
        }

    }

    public synchronized float[][] loadBlockData(int blockRowIdx, int blockColIdx) {

        String key = "row" + blockRowIdx + "_col" + blockColIdx;
        float[][] blockData = blockDataCache.get(key);
        if (blockData != null) return blockData;    // In case this was calculated in another thread

        SeekableStream is = null;
        try {
            is = SeekableStreamFactory.getInstance().getStreamFor(path);

            int pointsPerBlockRow = blockSize * dim;  // Applies to all but the last row

            int rowDim = blockRowIdx < nFullBlocks ? blockSize : remSize;
            int colDim = blockColIdx < nFullBlocks ? blockSize : remSize;

            int l1 = blockRowIdx * pointsPerBlockRow;
            int l2 = blockColIdx * blockSize * rowDim;
            long startFilePosition = arrayStartPosition + (l1 + l2) * 4l;


            int nDataPoints = rowDim * colDim;
            int nBytes = nDataPoints * 4;
            byte[] byteArray = new byte[nBytes];

            is.seek(startFilePosition);
            is.readFully(byteArray);

            ByteArrayInputStream bis = new ByteArrayInputStream(byteArray);
            LittleEndianInputStream les = new LittleEndianInputStream(bis);


            blockData = new float[rowDim][colDim];

            for (int r = 0; r < rowDim; r++) {
                for (int c = 0; c < colDim; c++) {
                    float f = les.readFloat();
                    blockData[r][c] = f;
                }
            }

            blockDataCache.put(key, blockData);
            return blockData;

        } catch (IOException e) {
            log.error("Error reading block data: " + blockRowIdx + "-" + blockColIdx, e);
            return null;
        } finally {
            if (is != null)
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
        }
    }


    @Override
    public int getRowDimension() {
        return dim;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public int getColumnDimension() {
        return dim;  //To change body of implemented methods use File | Settings | File Templates.
    }


    public float getLowerValue() {
        return lowerValue;
    }

    public float getUpperValue() {
        return upperValue;
    }

    @Override
    public void setEntry(int i, int j, float corr) {

    }

    private void init() throws IOException {
        // Peak at file to determine version
        BufferedInputStream bis = null;
        try {
            InputStream is = ParsingUtils.openInputStream(path);
            bis = new BufferedInputStream(is);
            LittleEndianInputStream les = new LittleEndianInputStream(bis);

            int bytePosition = 0;
            int magic = les.readInt();    // <= 6515048
            bytePosition += 4;

            //if (magic != 6515048)      <= ERROR
            // Version number
            int version = les.readInt();
            bytePosition += 4;

            genome = les.readString();
            bytePosition += genome.length() + 1;

            chr1 = les.readString();
            bytePosition += chr1.length() + 1;

            chr2 = les.readString();
            bytePosition += chr2.length() + 1;

            binSize = les.readInt();
            bytePosition += 4;

            lowerValue = les.readFloat();
            bytePosition += 4;

            upperValue = les.readFloat();
            bytePosition += 4;

            int nRows = les.readInt();  // # rows, assuming square matrix
            bytePosition += 4;

            int nCols = les.readInt();
            bytePosition += 4;

            if (nRows == nCols) {
                dim = nRows;
            } else {
                throw new RuntimeException("Non-square matrices not supported");
            }

            blockSize = les.readInt();
            bytePosition += 4;

            nFullBlocks = dim / blockSize;
            remSize = dim - nFullBlocks * blockSize;

            this.arrayStartPosition = bytePosition;


        } finally {
            if (bis != null)
                bis.close();
        }
    }

}