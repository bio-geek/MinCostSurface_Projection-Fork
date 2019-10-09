package de.mpicbg.scf.mincostsurface;

import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.ops.AbstractOp;
import net.imagej.ops.Op;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.img.Img;
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
 * Date: September 2019
 *
 * MinCostZSurfaceMT was experimental for implementing MinCost2ZSurface Multi-threaded version.
 * This class is not necessary as a single thread can deal with it in a fairly fast way.
 * Caution: too many threads gives an error as well.
 */
@Plugin(type = Op.class, menuPath = "Plugins>MinCostZSurface>Multi Threads", name="MinCostZSurfaceMT", headless = true, label="MinCostZSurfaceMT")
public class MinCostZSurfaceMT_Ops< T extends RealType<T> & NativeType< T >> extends AbstractOp {
    //
    // should implement ops if I want to benefit the matching mechanism
    //

    //@Parameter ( label = "input image" )
    //private ImagePlus imp_orig;

    @Parameter( label = "cost image" , persist = false )
    private Img input;


    // parameter related to the initial surface detection

    int channel = 1;

    @Parameter ( label = "rescale xy" )  // downsampling factor of the input image for the direction x and y
    private float downsample_factor_xy;

    @Parameter ( label = "rescale z" ) // downsampling factor of the input image for the direction z
    private float downsample_factor_z;

    @Parameter ( label = "Max_delta_z between adjacent voxel" ) // constraint on the surface altitude change from one pixel to another
    private int max_dz;

    //@Parameter( label = "output number of slice" ) // range of pixel grabbed around the surface to build the output
    //private int output_height;


    @Parameter ( label = "Number of Threads")
    private int numThreads;



    // output

    //@Parameter  (type = ItemIO.OUTPUT)
    //private Img<T> outputExcerpt;

    @Parameter  (type = ItemIO.OUTPUT)
    private Img<T> upsampled_depthMap;



    @Override
    public void run() {

        process( (Img<T>) input );

    }


    public void process( Img<T> input ){

        int nDim = input.numDimensions();
        if ( nDim != 3 ) {
            System.err.println("The data should be of dimensions 3 (found " + nDim + " dimensions)");
            return;
        }

        long end,start;

        long[] dims_orig = new long[nDim];
        input.dimensions( dims_orig );
        Img<T> image_cost_orig = input.copy();//ImagePlusAdapter.wrap(imp_cost_dup);


        ///////////////////////////////////////////////////////////////////////////////////////////////////
        // downsampling the input image ///////////////////////////////////////////////////////////////////
        Img<T> image_cost_ds = img_utils.downsample(image_cost_orig, new float[] {downsample_factor_xy, downsample_factor_xy, downsample_factor_z});
        //ImageJFunctions.show( image_cost_ds );

        final long[] dims = new long[ image_cost_ds.numDimensions() ];

        image_cost_ds.dimensions(dims);

        if(dims[1] < numThreads) {
            numThreads = (int) dims[1];
        }

//        dims[0] = image_cost_ds.dimension(0);
        dims[1] = image_cost_ds.dimension(1) / numThreads;

        FinalInterval interval = new FinalInterval(dims);

        Img< T > depth_map = processMT(image_cost_ds, interval, numThreads);

        ////////////////////////////////////////////////////////////////////////////////////////////////
        // up-sample the depth_map result //////////////////////////////////////////////////////////////
        upsampled_depthMap = img_utils.upsample(depth_map, new long[] { dims_orig[0], dims_orig[1]}, img_utils.Interpolator.Linear );

        // multiply the altitude value to compensate earlier z sampling
        Cursor<T> up_map_cursor = upsampled_depthMap.cursor();
        while(up_map_cursor.hasNext())
            up_map_cursor.next().mul(1/ downsample_factor_z);

        //ImageJFunctions.show( upsampled_depthMap, "altitude map" );



        //IJ.log("creating z surface reslice" );
        //outputExcerpt = img_utils.ZSurface_reslice(image_orig, upsampled_depthMap, output_height/2, output_height/2);
        //ImageJFunctions.show(excerpt, "excerpt");


        System.out.println("processing done");

    }

    < T extends RealType< T >> Img< T > processMT(final Img< T > inputSource, final Interval interval, final int numThreads )
    {
        final long[][] offset = createOffset(inputSource, numThreads);

//        final long[][] offset = new long[ numThreads ][inputSource.numDimensions()];
//
//        for ( int d = 0; d < offset.length; d++ )
//        {
//            offset[d] = new long[inputSource.numDimensions()];
//
//            for (int i = 0; i < offset[d].length; i++) {
//                offset[d][i] = 0;
//            }
//            // width
////            offset[d][0] = inputSource.dimension( 0 ) / numThreads * d;
//            // height
//            offset[d][1] = inputSource.dimension( 1 ) / numThreads * d;
//            // depth
////            offset[d][2] = 0;
//        }

        final Img< T > globalDepthMap = inputSource.factory().create(inputSource.dimension(0), inputSource.dimension(1));

        final Thread[] threads = SimpleMultiThreading.newThreads( numThreads );
        for ( int i = 0; i < threads.length; i++ )
        {
            int finalI = i;
            threads[ i ] = new Thread( "MinCostZSurface thread " + finalI)
            {
                @Override
                public void run()
                {
                    IntervalView< T > intervalView = Views.offset( inputSource, offset[finalI] );
                    final Img< T > part = inputSource.factory().create( interval );
                    Cursor< T > cursor = part.cursor();
                    RandomAccess< T > randomAccess = intervalView.randomAccess();

                    while ( cursor.hasNext() )
                    {
                        cursor.fwd();
                        randomAccess.setPosition( cursor );
                        cursor.get().set( randomAccess.get() );
                    }


                    ///////////////////////////////////////////////////////////////////////////////////////////////////
                    // creating a surface detector solver instance  ///////////////////////////////////////////////////
                    MinCostZSurface ZSurface_detector = new MinCostZSurface();

                    long end,start;
                    ///////////////////////////////////////////////////////////////////////////////////////////////////
                    // filling the surface graph for a single surface
                    start = System.currentTimeMillis();
                    ZSurface_detector.Create_Surface_Graph(part, max_dz);
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
                    // building the depth map ///////////////////////////////////////////////////////////////////
                    //IJ.log("n surfaces: " + ZSurface_detector.getNSurfaces() );
                    Img<FloatType> depth_map =  ZSurface_detector.get_Altitude_Map(1);

                    intervalView = Views.offset( globalDepthMap, new long[] { offset[finalI][0], offset[finalI][1] } );
                    final Cursor< FloatType > cursorDepthMap = depth_map.cursor();
                    randomAccess = intervalView.randomAccess();
                    while(cursorDepthMap.hasNext())
                    {
                        try {
                            cursorDepthMap.fwd();
                            randomAccess.setPosition( cursorDepthMap );
                            randomAccess.get().setReal( cursorDepthMap.get().getRealFloat() );
                        } catch (java.lang.ArrayIndexOutOfBoundsException e) {
                            continue;
                        }
                    }
                }
            };
        }

        SimpleMultiThreading.startAndJoin( threads );
        return globalDepthMap;
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
            ij.command().run(MinCostZSurfaceMT_Ops.class, true);
        }
    }

}