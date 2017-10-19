

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



//TODO:
// needs to be tested and t produce an adequate ouput. creating a roi would make a lot of sense here 

@Plugin(type = Op.class, menuPath = "Plugins>MinCostYCurve", name="MinCostYCurve", headless = true, label="MinCostYCurve")
public class MinCostYCurve_Ops< T extends RealType<T> & NativeType< T > > extends AbstractOp {
    

    
    @Parameter ( label = "cost image" , persist = false )
    private Img input;

   
    // parameter related to the initial surface detection
    
    int channel = 1;
    
    @Parameter ( label = "rescale x" )  // downsampling factor of the input image for the direction x and y
    private float downsample_factor_x;

    @Parameter ( label = "rescale y" ) // downsampling factor of the input image for the direction z
    private float downsample_factor_y;

    @Parameter ( label = "Max_delta_y between adjacent voxel" ) // constraint on the surface altitude change from one pixel to another
    private int max_dy;
    
 
    // output
    
   
    @Parameter  (type = ItemIO.OUTPUT)
    private Img<FloatType> upsampled_depthMap;
	
	  
	
    @Override
    public void run() {
    	
       	process( (Img<T>) input );
       	
    }

    
    public void process( Img<T> input ){
		
    	int nDim = input.numDimensions();
		if ( nDim != 2 ) {
			System.err.println("The data should be of dimensions 2 (found " + nDim + " dimensions)");
			return;
		}
		
		long end,start;
		
		long[] dims_orig = new long[nDim];
		input.dimensions( dims_orig );
		Img<T> image_cost_orig = input.copy();//ImagePlusAdapter.wrap(imp_cost_dup);
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// downsampling the input image ///////////////////////////////////////////////////////////////////
		Img<T> image_cost_ds = img_utils.downsample(image_cost_orig, new float[] {downsample_factor_x, downsample_factor_y});
		//ImageJFunctions.show( image_cost_ds );
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// creating a surface detector solver instance  ///////////////////////////////////////////////////
		MinCostYCurve<T> YCurve_detector = new MinCostYCurve<T>();
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// filling the surface graph for a single surface 
		start = System.currentTimeMillis();
		YCurve_detector.Create_Surface_Graph(image_cost_ds, max_dy);
		end = System.currentTimeMillis();
		System.out.println("...done inserting edges. (" + (end - start) + "ms)");
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// computing the maximum flow //////////////////////////////////////////////////////////////////////
		System.out.println("Calculating max flow");
		start = System.currentTimeMillis();
		
		YCurve_detector.Process();
		float maxFlow = YCurve_detector.getMaxFlow();
		
		end = System.currentTimeMillis();
		System.out.println("...done. Max flow is " + maxFlow + ". (" + (end - start) + "ms)");
		
		
		/////////////////////////////////////////////////////////////////////////////////////////////
		// building the depth map ///////////////////////////////////////////////////////////////////
		//IJ.log("n surfaces: " + ZSurface_detector.getNSurfaces() );
		Img<FloatType> depth_map =  YCurve_detector.get_Altitude_Map(1);
		
		
		////////////////////////////////////////////////////////////////////////////////////////////////
		// up-sample the depth_map result ////////////////////////////////////////////////////////////// 
		upsampled_depthMap = img_utils.upsample(depth_map, new long[] { dims_orig[0]}, img_utils.Interpolator.Linear );
	
		// multiply the altitude value to compensate earlier z sampling 
		Cursor<FloatType> up_map_cursor = upsampled_depthMap.cursor();
		while(up_map_cursor.hasNext())
			up_map_cursor.next().mul( 1 / downsample_factor_y);
		 
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
            ij.command().run(MinCostYCurve_Ops.class, true);
        }
    }

}
