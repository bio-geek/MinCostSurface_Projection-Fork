package de.mpicbg.scf.mincostsurface;


import ij.gui.GenericDialog;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.ImagePlusAdapter;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.Cursor;




public class MinCostZSurface_Plugin implements PlugIn{

	
	/**
	 * 
	 * This class implement an IJ/FIJI plugin to construct min cost surface in volumetric data. 
	 * The surface altitude z is a function f(x,y)
	 * 
	 * the algorithm is implemented after the method described in:
	 * Kang Li et al., "Optimal surface segmentation in volumetric images - a Graph-theoretic approach",
	 * IEEE transactions on pattern analysis and machine intelligence, vol 28, n 1, 2006.
	 * 
	 * - original data can be downsample to reduce computational cost
	 * - surface regularity can be constrained (delta dz/dx, dz/dy are limited to a finite range max_dz)
	 * - input is a cost image
	 * - output is:
	 * 		+ a volume where surface has value 1 and background the value zero (exactly one pixel per z column should be 1)
	 *  	or alternatively:
	 *  	+ a volume of range Rz around the surface showing the intensity of the image
	 *  
	 */
	
	/*
	 * TODO:
	 * + indicate the 3rd dim to be z direction and not channel when using ImageJFunctions.show(...) 
	 * + complete the javadoc 
	 */
	
	
	int imp_orig_idx; // index of the input image in the window manager
	int imp_cost_idx; // index of the cost image in the window manager
	int channel;//channel to process // force to one , the input image is expected to have one channel 
	float downsample_factor_xy; // downsampling factor of the input image for the direction x and y
	float downsample_factor_z;  // downsampling factor of the input image for the direction z
	int   max_dz;         // constraint on the surface altitude change from one pixel to another
	boolean process_2surf;
	int max_dist;
	int min_dist;
	boolean display_excerpt;
	int   output_height;       // range of pixel grabbed around the surface to build the output 
	
	@Override
	public void run(String arg) {
		if (showDialog())
		{	
			channel = 1;
			ImagePlus imp_cost = WindowManager.getImage(imp_cost_idx+1);
			ImagePlus imp_orig = WindowManager.getImage(imp_orig_idx+1);
			
			if ( !process_2surf )
				//test_refactoredClass(imp_orig, imp_cost);
				process(imp_orig, imp_cost);
			else
				process2(imp_orig, imp_cost);
		}
		
	}	
	
	
	
	
	public boolean showDialog() {
		
		// get the list of open images
		int OpenImageNum = WindowManager.getImageCount();
	    String[] OpenImageNames = new String[OpenImageNum];
	    for (int i = 0; i < OpenImageNum; i++)
	    	OpenImageNames[i] = WindowManager.getImage(i + 1).getTitle();
	    
		
		GenericDialog gd = new GenericDialog("Transcript Analysis");
		// input image channel
		gd.addChoice("input image", OpenImageNames, OpenImageNames[0]);
		gd.addChoice("cost image", OpenImageNames, OpenImageNames[0]);
		
		//gd.addNumericField("channel",1, 0);
		gd.addNumericField("rescale_x,y",0.25, 2);
		gd.addNumericField("rescale_z",0.5, 2);
		gd.addNumericField("Max_delta_z between adjacent voxel",1, 0);
		
		// display option
		gd.addCheckbox("display_volume(s) contiguous to the surface(s)", true);
		gd.addNumericField("volume number of slice ",10, 0);
		
		// two surface options
		gd.addCheckbox("two_surfaces", true);
		gd.addNumericField("Max_distance between surfaces (in pixel)",15, 0);
		gd.addNumericField("Min_distance between surfaces (in pixel) ",3, 0);
		
				
		gd.showDialog();
		if (gd.wasCanceled())
			return false;
		imp_orig_idx = gd.getNextChoiceIndex();
		imp_cost_idx = gd.getNextChoiceIndex();
		downsample_factor_xy = (float) gd.getNextNumber();
		downsample_factor_z  = (float) gd.getNextNumber();
		max_dz =  (int) gd.getNextNumber();
		
		display_excerpt = gd.getNextBoolean();
		output_height = (int) gd.getNextNumber();
		
		process_2surf = gd.getNextBoolean();
		max_dist = (int) gd.getNextNumber();
		min_dist =  (int) gd.getNextNumber();
		
		
		return true;
	}
	
	



	public < T extends RealType<T> & NumericType< T > & NativeType< T > > void process(ImagePlus imp_orig, ImagePlus imp_cost){
		
		int nDim = 3;
		long end,start;
		int[] dims_orig = imp_cost.getDimensions();
		
		ImagePlus imp_cost_dup = new Duplicator().run( imp_cost, channel, channel, 1, imp_cost.getNSlices(), 1, 1);
		
		Img<T> image_cost_orig = ImagePlusAdapter.wrap(imp_cost_dup);
		Img<T> image_orig = ImagePlusAdapter.wrap(imp_orig);
		if( image_cost_orig.numDimensions()!=nDim ){ IJ.log("the image should be of dimension 3"); return;}
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// downsampling the input image ///////////////////////////////////////////////////////////////////
		Img<T> image_cost_ds = img_utils.downsample(image_cost_orig, new float[] {downsample_factor_xy, downsample_factor_xy, downsample_factor_z});
		ImageJFunctions.show( image_cost_ds );
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// creating a surface detector solver instance  ///////////////////////////////////////////////////
		MinCostZSurface<T> ZSurface_detector = new MinCostZSurface<T>();
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// filling the surface graph for a single surface 
		start = System.currentTimeMillis();
		ZSurface_detector.Create_Surface_Graph(image_cost_ds, max_dz);
		end = System.currentTimeMillis();
		IJ.log("...done inserting edges. (" + (end - start) + "ms)");
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// computing the maximum flow //////////////////////////////////////////////////////////////////////
		IJ.log("Calculating max flow");
		start = System.currentTimeMillis();
		
		ZSurface_detector.Process();
		float maxFlow = ZSurface_detector.getMaxFlow();
		
		end = System.currentTimeMillis();
		IJ.log("...done. Max flow is " + maxFlow + ". (" + (end - start) + "ms)");
		
		
		/////////////////////////////////////////////////////////////////////////////////////////////
		// building the depth map ///////////////////////////////////////////////////////////////////
		//IJ.log("n surfaces: " + ZSurface_detector.getNSurfaces() );
		Img<FloatType> depth_map =  ZSurface_detector.get_Altitude_Map(1);
		
		
		////////////////////////////////////////////////////////////////////////////////////////////////
		// up-sample the depth_map result ////////////////////////////////////////////////////////////// 
		Img<FloatType> upsampled_depthMap = img_utils.upsample(depth_map, new long[] { dims_orig[0], dims_orig[1]}, img_utils.Interpolator.Linear );
	
		// multiply the altitude value to compensate earlier z sampling 
		Cursor<FloatType> up_map_cursor = upsampled_depthMap.cursor();
		while(up_map_cursor.hasNext())
			up_map_cursor.next().mul(1/ downsample_factor_z);
		 
		ImageJFunctions.show( upsampled_depthMap, "altitude map" );
		
		
		if (display_excerpt)
		{
			IJ.log("creating z surface reslice" );
			Img<T> excerpt = img_utils.ZSurface_reslice(image_orig, upsampled_depthMap, output_height/2, output_height/2);
			ImageJFunctions.show(excerpt, "excerpt");
		}
		
		IJ.log("processing done");
		
	}
	

	public < T extends RealType<T> & NumericType< T > & NativeType< T > > void process2(ImagePlus imp_orig, ImagePlus imp_cost){
		
		int nDim = 3;
		long end,start;
		int[] dims_orig = imp_cost.getDimensions();
		
		ImagePlus imp_cost_dup = new Duplicator().run( imp_cost, channel, channel, 1, imp_cost.getNSlices(), 1, 1);
		
		Img<T> image_cost_orig = ImagePlusAdapter.wrap(imp_cost_dup);
		Img<T> image_orig = ImagePlusAdapter.wrap(imp_orig);
		if( image_cost_orig.numDimensions()!=nDim ){ IJ.log("the image should be of dimension 3"); return;}
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// downsampling the input image ///////////////////////////////////////////////////////////////////
		Img<T> image_cost_ds = img_utils.downsample(image_cost_orig, new float[] {downsample_factor_xy, downsample_factor_xy, downsample_factor_z});
		ImageJFunctions.show( image_cost_ds );
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// creating a surface detector solver instance  ///////////////////////////////////////////////////
		MinCostZSurface<T> ZSurface_detector = new MinCostZSurface<T>();
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// filling the surface graph for a single surface 
		start = System.currentTimeMillis();
		
		ZSurface_detector.Create_Surface_Graph(image_cost_ds, max_dz);
		ZSurface_detector.Create_Surface_Graph(image_cost_ds, max_dz);
		ZSurface_detector.Add_NoCrossing_Constraint_Between_Surfaces(1, 2, min_dist, max_dist);
		
		end = System.currentTimeMillis();
		IJ.log("...done inserting edges. (" + (end - start) + "ms)");
		
		
		///////////////////////////////////////////////////////////////////////////////////////////////////
		// computing the maximum flow //////////////////////////////////////////////////////////////////////
		IJ.log("Calculating max flow");
		start = System.currentTimeMillis();
		
		ZSurface_detector.Process();
		float maxFlow = ZSurface_detector.getMaxFlow();
		
		end = System.currentTimeMillis();
		IJ.log("...done. Max flow is " + maxFlow + ". (" + (end - start) + "ms)");
		
		
		/////////////////////////////////////////////////////////////////////////////////////////////
		// building the depth map, upsample the result 	and display it //////////////////////////////
		//IJ.log("n surfaces: " + ZSurface_detector.getNSurfaces() );
		Img<FloatType> depth_map1 =  ZSurface_detector.get_Altitude_Map(1);
		Img<FloatType> depth_map2 =  ZSurface_detector.get_Altitude_Map(2);
		
		Img<FloatType> upsampled_depthMap1 = img_utils.upsample(depth_map1, new long[] { dims_orig[0], dims_orig[1]}, img_utils.Interpolator.Linear );
		Img<FloatType> upsampled_depthMap2 = img_utils.upsample(depth_map2, new long[] { dims_orig[0], dims_orig[1]}, img_utils.Interpolator.Linear );
	
		Cursor<FloatType> up_map_cursor1 = upsampled_depthMap1.cursor();
		Cursor<FloatType> up_map_cursor2 = upsampled_depthMap2.cursor();
		while(up_map_cursor1.hasNext())
		{
			up_map_cursor1.next().mul(1/ downsample_factor_z);
			up_map_cursor2.next().mul(1/ downsample_factor_z);
		}
		ImageJFunctions.show( upsampled_depthMap1, "altitude map1" );
		ImageJFunctions.show( upsampled_depthMap2, "altitude map2" );
		
		
		if (display_excerpt)
		{
			IJ.log("creating z surface reslice" );
			Img<T> excerpt1 = img_utils.ZSurface_reslice(image_orig, upsampled_depthMap1, output_height/2, output_height/2);
			ImageJFunctions.show(excerpt1, "excerpt");
			Img<T> excerpt2 = img_utils.ZSurface_reslice(image_orig, upsampled_depthMap2, output_height/2, output_height/2);
			ImageJFunctions.show(excerpt2, "excerpt");
		}
		
		IJ.log("processing done");
		
	}




	public static void main(final String... args)
	{
		new ij.ImageJ();
		IJ.open( "");
		ImagePlus imp = IJ.getImage();
		
		// create a cost image
		ImagePlus imp_dup = new Duplicator().run( imp, 1, 1, 1, imp.getNSlices(), 1, 1);
		IJ.run(imp_dup,"Invert","stack");
		imp_dup.show();
		
		//IJ.run("Record...", "");
		
		// start the plugin
		new MinCostZSurface_Plugin().run("");
	}



}

