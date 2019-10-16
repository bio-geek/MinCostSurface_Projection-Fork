package de.mpicbg.scf.mincostsurface;

import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.ops.AbstractOp;
import net.imagej.ops.Op;
import net.imglib2.*;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.scijava.ItemIO;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

import static de.mpicbg.scf.mincostsurface.img_utils.createOffset;

/**
 * Author: HongKee Moon (moon@mpi-cbg.de), Scientific Computing Facility
 * Organization: MPI-CBG Dresden
 * Date: October 2019
 *
 * Multi threaded version MinCost2ZSurface
 * The number of threads field is added.
 * Increasing the no. of threads will improve the speed of the processing.
 *
 * The image chunks are made by height / numThreads. Each chunk is processed in MinCostZSurface class
 */
@Plugin(type = Op.class, menuPath = "Plugins>MinCost2ZSurface>Multi Threads", name = "MinCost2ZSurfaceMT", headless = true, label = "MinCost2ZSurfaceMT")
public class MinCost2ZSurfaceMT_Ops<T extends RealType<T> & NativeType<T>> extends AbstractOp {
    //
    // should implement Map if I want to benefit the ops matching
    //

    @Parameter(label = "cost image", persist = false)
    private Img input;


    // parameter related to the initial surface detection

    int channel = 1;

    @Parameter(label = "rescale xy")  // downsampling factor of the input image for the direction x and y
    private float downsample_factor_xy;

    @Parameter(label = "rescale z") // downsampling factor of the input image for the direction z
    private float downsample_factor_z;

    @Parameter(label = "Max_delta_z between adjacent voxel")
    // constraint on the surface altitude change from one pixel to another
    private int max_dz;

    @Parameter(label = "relative weight", required = false, persist = false)
    // multiplicative factor that balance the intensity in both surfaces allowing better detection
    private float relativeIntensity = 1f;

    // parameter for the use case with 2 surfaces detection

    @Parameter(label = "Max_distance between surfaces (in pixel)")
    private int max_dist;

    @Parameter(label = "Min_distance between surfaces (in pixel)")
    private int min_dist;

    @Parameter(label = "Number of Threads")
    private int numThreads;


    // output
    @Parameter(type = ItemIO.OUTPUT)
    private Img<T> upsampled_depthMap1;

    @Parameter(type = ItemIO.OUTPUT)
    private Img<T> upsampled_depthMap2;


    @Override
    public void run() {
        process2((Img<T>) input);
    }

    public void process2(Img<T> input) {

        int nDim = input.numDimensions();
        if (nDim != 3) {
            System.err.println("The data should be of dimensions 3 (found " + nDim + " dimensions)");
            return;
        }

        long[] dims_orig = new long[nDim];
        input.dimensions(dims_orig);

        Img<T> image_cost_orig = input.copy();

        ///////////////////////////////////////////////////////////////////////////////////////////////////
        // downsampling the input image ///////////////////////////////////////////////////////////////////
        Img<T> image_cost_ds = img_utils.downsample(image_cost_orig, new float[]{downsample_factor_xy, downsample_factor_xy, downsample_factor_z});

        final long[] dims = new long[image_cost_ds.numDimensions()];
        final long[] lastDims = new long[image_cost_ds.numDimensions()];

        image_cost_ds.dimensions(dims);
        image_cost_ds.dimensions(lastDims);

        if(dims[1] < numThreads || numThreads < 0) {
            numThreads = (int) dims[1];
        }

//        System.out.println("Height: " + dims[1]);

        // Setup the number of dimension in the input image except the height part
        // The height will be the original height divided by the number of threads
        dims[1] = image_cost_ds.dimension(1) / numThreads;

        lastDims[1] = image_cost_ds.dimension(1) - dims[1] * (numThreads - 1);
        FinalInterval interval = new FinalInterval(dims);
        FinalInterval lastInterval = new FinalInterval(lastDims);

        // Call the Multi threaded process
//        Img<T>[] depth_map = processMT(image_cost_ds, interval, numThreads);
        Img<T>[] depth_map = processMT(image_cost_ds, interval, lastInterval, numThreads);

        // The returned depth map array
        final Img<T> depth_map1 = depth_map[0];
        final Img<T> depth_map2 = depth_map[1];

        // Upsampling the depth map images
        upsampled_depthMap1 = img_utils.upsample(depth_map1, new long[]{dims_orig[0], dims_orig[1]}, img_utils.Interpolator.Linear);
        upsampled_depthMap2 = img_utils.upsample(depth_map2, new long[]{dims_orig[0], dims_orig[1]}, img_utils.Interpolator.Linear);


//        ImageJFunctions.show( depth_map1, "altitude map1" );
//        ImageJFunctions.show( depth_map2, "altitude map2" );


        Cursor<T> up_map_cursor1 = upsampled_depthMap1.cursor();
        Cursor<T> up_map_cursor2 = upsampled_depthMap2.cursor();
        while (up_map_cursor1.hasNext()) {
            up_map_cursor1.next().mul(1 / downsample_factor_z);
            up_map_cursor2.next().mul(1 / downsample_factor_z);
        }

//        ImageJFunctions.show(upsampled_depthMap1,"altitude map1");
//        ImageJFunctions.show(upsampled_depthMap2,"altitude map2" );

        System.out.println("processing done");
    }


    <T extends RealType<T> & NativeType<T>> Img<T>[] processMT(final Img<T> inputSource, Interval interval, final Interval lastInterval, final int numThreads) {
//    <T extends RealType<T> & NativeType<T>> Img<T>[] processMT(final Img<T> inputSource, final Interval interval, final int numThreads) {

        // Setup the offset arrays for multi threads
        final long[][] offset = createOffset(inputSource, numThreads);

        // Initialize depth maps
        final Img<T> globalDepthMap1 = inputSource.factory().create(inputSource.dimension(0), inputSource.dimension(1));
        final Img<T> globalDepthMap2 = inputSource.factory().create(inputSource.dimension(0), inputSource.dimension(1));

        // Setup the multi threads
        final Thread[] threads = SimpleMultiThreading.newThreads(numThreads);
        for (int i = 0; i < threads.length; i++) {
            int finalI = i;
            threads[i] = new Thread("MinCostZSurface thread " + finalI) {
                @Override
                public void run() {
                    // Setup the IntervalView of the original image
                    IntervalView<T> intervalView = Views.offset(inputSource, offset[finalI]);
                    // Setup the chunk for each thread
//                    Img<T> chunk = inputSource.factory().create(interval);
                    Img<T> chunk;
                    if(finalI == (threads.length - 1)) chunk = inputSource.factory().create(lastInterval);
                    else chunk = inputSource.factory().create(interval);

                    Cursor<T> cursor = chunk.localizingCursor();
                    RandomAccess<T> randomAccess = intervalView.randomAccess();

                    // Copy the original values to the chunk
                    while (cursor.hasNext()) {
                        cursor.fwd();
                        randomAccess.setPosition(cursor);
                        cursor.get().set(randomAccess.get());
                    }


                    long end, start;

                    ///////////////////////////////////////////////////////////////////////////////////////////////////
                    // creating a surface detector solver instance  ///////////////////////////////////////////////////
                    // by using the pre-defined chunk
                    MinCostZSurface<T> ZSurface_detector = new MinCostZSurface<T>();


                    ///////////////////////////////////////////////////////////////////////////////////////////////////
                    // filling the surface graph for a single surface
                    start = System.currentTimeMillis();

                    ZSurface_detector.Create_Surface_Graph(chunk, max_dz);
                    ZSurface_detector.Create_Surface_Graph(chunk, max_dz, relativeIntensity);
                    ZSurface_detector.Add_NoCrossing_Constraint_Between_Surfaces(1, 2, min_dist, max_dist);

                    end = System.currentTimeMillis();
                    System.out.println("...done inserting edges. (" + (end - start) + "ms)");


                    ///////////////////////////////////////////////////////////////////////////////////////////////////
                    // computing the maximum flow //////////////////////////////////////////////////////////////////////
                    System.out.println("Calculating max flow");
                    start = System.currentTimeMillis();

                    ZSurface_detector.Process();
                    float maxFlow = ZSurface_detector.getMaxFlow();

                    end = System.currentTimeMillis();
                    System.out.println("...done. Max flow is " + maxFlow + ". (" + (end - start) + "ms)");


                    /////////////////////////////////////////////////////////////////////////////////////////////
                    // building the depth map, upsample the result 	and display it //////////////////////////////
                    //IJ.log("n surfaces: " + ZSurface_detector.getNSurfaces() );
                    // Collect the depth maps from surface 1 and 2
                    Img<FloatType> depth_map1 = ZSurface_detector.get_Altitude_Map(1);
                    Img<FloatType> depth_map2 = ZSurface_detector.get_Altitude_Map(2);


                    // Setup the IntervalView for the global depth map 1 and 2 by using the offset values
                    IntervalView<T> intervalView1 = Views.offset(globalDepthMap1, new long[]{offset[finalI][0], offset[finalI][1]});
                    IntervalView<T> intervalView2 = Views.offset(globalDepthMap2, new long[]{offset[finalI][0], offset[finalI][1]});
                    final Cursor<FloatType> cursorDepthMap1 = depth_map1.cursor();
                    final Cursor<FloatType> cursorDepthMap2 = depth_map2.cursor();
                    RandomAccess<T> randomAccess1 = intervalView1.randomAccess();
                    RandomAccess<T> randomAccess2 = intervalView2.randomAccess();

                    // Copy the values from the chunk to the global maps
                    while (cursorDepthMap1.hasNext()) {
                        cursorDepthMap1.fwd();
                        randomAccess1.setPosition(cursorDepthMap1);
                        randomAccess1.get().setReal(cursorDepthMap1.get().getRealFloat());

                        cursorDepthMap2.fwd();
                        randomAccess2.setPosition(cursorDepthMap2);
                        randomAccess2.get().setReal(cursorDepthMap2.get().getRealFloat());
                    }
                }
            };
        }

        // Wait for the all the threads finish
        SimpleMultiThreading.startAndJoin(threads);
        // Return the global depth maps
        return new Img[]{globalDepthMap1, globalDepthMap2};
    }


    public static void main(final String... args) throws Exception {
        // create the ImageJ application context with all available services
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();

        // ask the user for a file to open
        final File file = ij.ui().chooseFile(null, "open");

        if (file != null) {
            // load the dataset
            final Dataset dataset = ij.scifio().datasetIO().open(file.getPath());

            // show the image
            ij.ui().show(dataset);

            // invoke the plugin
            ij.command().run(MinCost2ZSurfaceMT_Ops.class, true);
        }
    }

}
