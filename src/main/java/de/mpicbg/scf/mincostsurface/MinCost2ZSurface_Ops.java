

package de.mpicbg.scf.mincostsurface;

import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.ops.Op;
import net.imagej.ops.AbstractOp;

import net.imglib2.Cursor;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

import org.scijava.ItemIO;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;


import java.io.File;


@Plugin(type = Op.class, menuPath = "Plugins>MinCost2ZSurface", name="MinCost2ZSurface", headless = true, label="MinCost2ZSurface")
public class MinCost2ZSurface_Ops < T extends RealType<T> & NativeType< T > > extends AbstractOp {
    //
    // should implement Map if I want to benefit the ops matching
    //

    @Parameter ( label = "cost image", persist = false )
    private Img input;

   
    // parameter related to the initial surface detection
    
    int channel = 1;
    
    @Parameter ( label = "rescale xy" )  // downsampling factor of the input image for the direction x and y
    private float downsample_factor_xy;

    @Parameter ( label = "rescale z" ) // downsampling factor of the input image for the direction z
    private float downsample_factor_z;

    @Parameter ( label = "Max_delta_z between adjacent voxel" ) // constraint on the surface altitude change from one pixel to another
    private int max_dz;
    
    @Parameter ( label = "relative weight", required=false, persist=false ) // multiplicative factor that balance the intensity in both surfaces allowing better detection 
    private float relativeIntensity = 1f ;
    
    // parameter for the use case with 2 surfaces detection
   	
    @Parameter( label = "Max_distance between surfaces (in pixel)" )
    private int max_dist;

    @Parameter( label = "Min_distance between surfaces (in pixel)" )
    private int min_dist;
    
    
    
    // output
    
    @Parameter  (type = ItemIO.OUTPUT)
    private Img<FloatType> upsampled_depthMap1;
	
    @Parameter  (type = ItemIO.OUTPUT)
    private Img<FloatType> upsampled_depthMap2;
	  
	
    
    
    
    
    
    @Override
    public void run() {
        	
    	process2( (Img<T>) input );	
       
    }

    
    
    
   
    
    
    
    public void process2( Img<T> input ){
		
		int nDim = input.numDimensions();
		if ( nDim != 3 ) {
			System.err.println("The data should be of dimensions 3 (found " + nDim + " dimensions)");
			return;
		}
		
		long end,start;
		
		long[] dims_orig = new long[nDim];
		input.dimensions( dims_orig );
		
		Img<T> image_cost_orig = input.copy();
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// downsampling the input image ///////////////////////////////////////////////////////////////////
		Img<T> image_cost_ds = img_utils.downsample(image_cost_orig, new float[] {downsample_factor_xy, downsample_factor_xy, downsample_factor_z});
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// creating a surface detector solver instance  ///////////////////////////////////////////////////
		MinCostZSurface<T> ZSurface_detector = new MinCostZSurface<T>();
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// filling the surface graph for a single surface 
		start = System.currentTimeMillis();
		
		ZSurface_detector.Create_Surface_Graph(image_cost_ds, max_dz);
		ZSurface_detector.Create_Surface_Graph(image_cost_ds, max_dz, relativeIntensity);
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
		Img<FloatType> depth_map1 =  ZSurface_detector.get_Altitude_Map(1);
		Img<FloatType> depth_map2 =  ZSurface_detector.get_Altitude_Map(2);
		
		upsampled_depthMap1 = img_utils.upsample(depth_map1, new long[] { dims_orig[0], dims_orig[1]}, img_utils.Interpolator.Linear );
		upsampled_depthMap2 = img_utils.upsample(depth_map2, new long[] { dims_orig[0], dims_orig[1]}, img_utils.Interpolator.Linear );
		
		//ImageJFunctions.show( depth_map1, "altitude map1" );
		//ImageJFunctions.show( depth_map2, "altitude map2" );
		
		
		Cursor<FloatType> up_map_cursor1 = upsampled_depthMap1.cursor();
		Cursor<FloatType> up_map_cursor2 = upsampled_depthMap2.cursor();
		while(up_map_cursor1.hasNext())
		{
			up_map_cursor1.next().mul(1/ downsample_factor_z);
			up_map_cursor2.next().mul(1/ downsample_factor_z);
		}
		
		
		//IJ.log("creating z surface reslice" );
		//outputExcerpt1 = img_utils.ZSurface_reslice(image_orig, upsampled_depthMap1, output_height/2, output_height/2);
		//ImageJFunctions.show(excerpt1, "excerpt");
		//outputExcerpt2 = img_utils.ZSurface_reslice(image_orig, upsampled_depthMap2, output_height/2, output_height/2);
		//ImageJFunctions.show(excerpt2, "excerpt");
		
		System.out.println("processing done");
		
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
            ij.command().run(MinCost2ZSurface_Ops.class, true);
        }
    }

}
