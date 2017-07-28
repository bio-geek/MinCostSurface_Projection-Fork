

package de.mpicbg.scf.mincostsurface;

import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.ops.AbstractOp;
import net.imagej.ops.Op;

import net.imglib2.Cursor;
import net.imglib2.img.Img;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

import org.scijava.ItemIO;
//import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;



import java.io.File;


@Plugin(type = Op.class, menuPath = "Plugins>MinCostZSurface", name="MinCostZSurface", headless = true, label="MinCostZSurface")
public class MinCostZSurface_Ops< T extends RealType<T> & NativeType< T > > extends AbstractOp {
    //
    // should implement ops if I want to benefit the matching mechanism
    //

    //@Parameter ( label = "input image" )
    //private ImagePlus imp_orig;

    @Parameter ( label = "cost image" , persist = false )
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

    
    
    
    
    // output
    
    //@Parameter  (type = ItemIO.OUTPUT)
    //private Img<T> outputExcerpt;

    @Parameter  (type = ItemIO.OUTPUT)
    private Img<FloatType> upsampled_depthMap;
	
	  
	
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
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// creating a surface detector solver instance  ///////////////////////////////////////////////////
		MinCostZSurface<T> ZSurface_detector = new MinCostZSurface<T>();
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// filling the surface graph for a single surface 
		start = System.currentTimeMillis();
		ZSurface_detector.Create_Surface_Graph(image_cost_ds, max_dz);
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
		
		
		////////////////////////////////////////////////////////////////////////////////////////////////
		// up-sample the depth_map result ////////////////////////////////////////////////////////////// 
		upsampled_depthMap = img_utils.upsample(depth_map, new long[] { dims_orig[0], dims_orig[1]}, img_utils.Interpolator.Linear );
	
		// multiply the altitude value to compensate earlier z sampling 
		Cursor<FloatType> up_map_cursor = upsampled_depthMap.cursor();
		while(up_map_cursor.hasNext())
			up_map_cursor.next().mul(1/ downsample_factor_z);
		 
		//ImageJFunctions.show( upsampled_depthMap, "altitude map" );
		
		
		
		//IJ.log("creating z surface reslice" );
		//outputExcerpt = img_utils.ZSurface_reslice(image_orig, upsampled_depthMap, output_height/2, output_height/2);
		//ImageJFunctions.show(excerpt, "excerpt");
		
		
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
            ij.command().run(MinCostZSurface_Ops.class, true);
        }
    }

}
