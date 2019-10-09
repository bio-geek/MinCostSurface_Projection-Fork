package de.mpicbg.scf.mincostsurface;


import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RealRandomAccess;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.interpolation.randomaccess.LanczosInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;

// Todo: add the volume reslice

public class img_utils {
	
	// what happens to the original image here
	public static <T extends RealType<T> & NativeType<T>> Img<T> downsample(Img<T> input, float[] ratio)
	{
		
		int nDim = input.numDimensions();
		//IJ.log("nDim "+nDim);
		double[] sig = new double[nDim];
		for( int i=0; i<nDim; i++){ sig[i] = 1/(2*ratio[i]); }
		
		long[] input_range = new long[2*nDim]; for(int i=0; i<nDim; i++){	input_range[i]=0; input_range[i+nDim] = input.dimension(i); }
		Interval interval = Intervals.createMinSize(input_range);
		try {
			Gauss3.gauss(sig,Views.extendMirrorDouble(input), Views.interval(input, interval) );
		} catch (IncompatibleTypeException e) {
			e.printStackTrace();
		}
		
		// create a new image
		final ImgFactory< T > imgFactory = new ArrayImgFactory< T >();
		long[] out_dims = new long[nDim]; for(int i=0; i<nDim; i++){ out_dims[i] = (int)(input.dimension(i)*ratio[i]);} 
		Img<T> output = imgFactory.create( out_dims , input.firstElement().createVariable() );
		
		// iterate through the image
		RandomAccess< T > input_x = Views.extendBorder( input ).randomAccess();
		Cursor< T > out_cursor = output.localizingCursor();
		int[] out_pos = new int[nDim];
		int[] in_pos = new int[nDim];
		while(out_cursor.hasNext())
		{
			out_cursor.fwd();
			out_cursor.localize(out_pos);
			for(int i=0; i<nDim; i++){ in_pos[i] = (int)(out_pos[i]/ratio[i]); }
			input_x.setPosition(in_pos);
			out_cursor.get().setReal( input_x.get().getRealFloat() );
		}
		
		return output;
	}
	
	
	
	/**
	 * this function down-sample the input image and return an image which size is input.dimension(i)*ratio[i])
	 * Prior to down-sampling the input is blurred to avoid aliasing
	 * @param input an image to down-sample
	 * @param ratio the down-sampling factor for each dimension (value should be inferior to 1 for down-sampling)
	 * @return a down-sampled version of input
	 */
	public static <T extends RealType<T> & NativeType<T>> Img<T> downsample2(Img<T> input, float[] ratio)
	{
		
		int nDim = input.numDimensions();
		double[] sig = new double[nDim];
		for( int i=0; i<nDim; i++){ sig[i] = 1/(2*ratio[i]); }
		
		long[] input_range = new long[2*nDim]; for(int i=0; i<nDim; i++){	input_range[i]=0; input_range[i+nDim] = input.dimension(i); }
		Interval interval = Intervals.createMinSize(input_range);
		try {
			Gauss3.gauss(sig,Views.extendMirrorDouble(input), Views.interval(input, interval) );
		} catch (IncompatibleTypeException e) {
			e.printStackTrace();
		}
		
		return decimate( input, ratio);
	}
	
	
	/**
	 * this function down-sample the input image and return an image which size is input.dimension(i)*ratio[i])
	 * @param input an image to down-sample
	 * @param ratio the down-sampling factor for each dimension (value should be inferior to 1 for down-sampling)
	 * @return a down-sampled version of input
	 */
	private static < T extends RealType<T> & NativeType< T > > Img<T> decimate(Img<T> input, float[] ratio)
	{
		// create a new image
		int nDim = input.numDimensions();		
		long[] out_dims = new long[nDim]; for(int i=0; i<nDim; i++){ out_dims[i] = (int)(input.dimension(i)*ratio[i]);} 
		Img<T> output = input.factory().create( out_dims , input.firstElement().createVariable() );
				
		// iterate through the image
		RandomAccess< T > input_RA = input.randomAccess();// Views.extendBorder( input ).randomAccess();
		Cursor< T > out_cursor = output.localizingCursor();
		int[] out_pos = new int[nDim];
		int[] in_pos = new int[nDim];
		while(out_cursor.hasNext())
		{
			out_cursor.fwd();
			out_cursor.localize(out_pos);
			for(int i=0; i<nDim; i++){ in_pos[i] = (int)(out_pos[i]/ratio[i]); }
			input_RA.setPosition(in_pos);
			out_cursor.get().setReal( input_RA.get().getRealFloat() );
		}
				
		return output;
	}
	
	
		
	public static enum Interpolator
	{
		NearestNeighbor,
		Linear,
		Lanczos;	
	}
	
	public static <T extends RealType<T> & NativeType<T>> Img<T> upsample(Img<T> input, long[] out_size, Interpolator interpType)
	{
		int nDim = input.numDimensions(); 
		if(nDim != out_size.length)
		{
			//print("upsampling error: the new size and input have a different number of dimension");
			System.err.println("upsampling error: the new size and input have a different number of dimension");
			return input;
		}
		long[] in_size = new long[nDim];
		input.dimensions(in_size);
		float[] upfactor = new float[nDim]; for(int i=0; i<nDim; i++){ upfactor[i] = (float)out_size[i]/in_size[i];}
		
		RealRandomAccess< T > interpolant;
		switch(interpType)
		{
			case Linear:
				NLinearInterpolatorFactory<T> NLinterp_factory = new NLinearInterpolatorFactory<T>();
				interpolant = Views.interpolate( Views.extendBorder( input ), NLinterp_factory ).realRandomAccess();
				break;
				
			case Lanczos:
				LanczosInterpolatorFactory<T> LanczosInterp_factory = new LanczosInterpolatorFactory<T>();
				interpolant = Views.interpolate( Views.extendBorder( input ), LanczosInterp_factory ).realRandomAccess();
				break;
				
			default: // NearestNeighbor:
				NearestNeighborInterpolatorFactory<T> NNInterp_factory = new NearestNeighborInterpolatorFactory<T>();
				interpolant = Views.interpolate( Views.extendBorder( input ), NNInterp_factory ).realRandomAccess();
				break;
		}
		
		final ImgFactory< T > imgFactory = new ArrayImgFactory< T >();
		
		final Img< T > output = imgFactory.create( out_size , input.firstElement().createVariable() );
		Cursor< T > out_cursor = output.localizingCursor();
		
		float[] tmp = new float[2]; 
		while(out_cursor.hasNext())
		{
			out_cursor.fwd();
			for ( int d = 0; d < nDim; ++d )
                tmp[ d ] = out_cursor.getFloatPosition(d) /upfactor[d];
			interpolant.setPosition(tmp);
			out_cursor.get().setReal(  interpolant.get().getRealFloat()  );
		}
		
		return output;
	}
	

	public static <T extends RealType<T> & NativeType<T>> Img<T> upsample(Img<T> input, float[] upsampling_factor, Interpolator interpType)
	{
		int dimension=0;
		input.dimension(dimension);
		long[] dims = new long[dimension];		
		input.dimensions(dims);

		long[] out_size = new long[dimension];
		for(int i=0; i<dimension; i++)
			out_size[i] = (long) (  (float)dims[i] * upsampling_factor[i]  );
		
		return upsample( input, out_size, interpType);
	}
	
	
	public static <T extends NumericType<T> & NativeType<T> , U extends RealType<U> >
	Img<T> ZSurface_reslice2(Img<T> input, Img<U> depthMap, int sliceOnTop, int sliceBelow)
	{
		RandomAccess< U > depthMapx = Views.extendBorder( depthMap ).randomAccess();
		//RandomAccess< T > inputx = Views.extendZero( input ).randomAccess();
		
		NLinearInterpolatorFactory<T> NLinterp_factory = new NLinearInterpolatorFactory<T>();
		RealRandomAccess< T > inputx_Real = Views.interpolate( Views.extendBorder( input ), NLinterp_factory ).realRandomAccess();
		
		
		int nDim = input.numDimensions();
		long[] dims = new long[nDim];
		input.dimensions(dims);
		long output_height = sliceOnTop + sliceBelow + 1;
		
		final ImgFactory< T > imgFactory = new ArrayImgFactory< T >();
		final Img< T > excerpt = imgFactory.create( new long[] {dims[0],dims[1], output_height} , input.firstElement().createVariable() );
		Cursor< T > excerpt_cursor = excerpt.localizingCursor();

		
		int[] tmp_pos = new int[nDim];
		float z_map;
		while(excerpt_cursor.hasNext())
		{
			excerpt_cursor.fwd();
			excerpt_cursor.localize(tmp_pos);
			depthMapx.setPosition(new int[] {tmp_pos[0],tmp_pos[1]});
			z_map =  depthMapx.get().getRealFloat();
			
			inputx_Real.setPosition(new float[] {(float)tmp_pos[0],(float)tmp_pos[1], (float)(tmp_pos[2]-(sliceOnTop)) + z_map });
			excerpt_cursor.get().set( inputx_Real.get() );
		}
		
		return excerpt;
	}

	public static <T extends NumericType<T> & NativeType<T> , U extends RealType<U> >
	Img<T> ZSurface_reslice3(Img<T> input, Img<U> depthMap, int sliceOnTop, int sliceBelow, int numThreads)
	{
		final long[][] inputOffset = createOffset(input, numThreads);

		int nDim = input.numDimensions();
		long[] dims = new long[nDim];
		input.dimensions(dims);
		long output_height = sliceOnTop + sliceBelow + 1;

		final ImgFactory< T > imgFactory = new ArrayImgFactory< T >();
		final Img< T > excerpt = imgFactory.create( new long[] {dims[0],dims[1], output_height} , input.firstElement().createVariable() );
		long unit = dims[1] / numThreads;

		final long[][] offset = createOffset(excerpt, numThreads);

		final long[][] depthMapOffset = createOffset(depthMap, numThreads);

		NLinearInterpolatorFactory<T> NLinterp_factory = new NLinearInterpolatorFactory<T>();
		final Thread[] threads = SimpleMultiThreading.newThreads( numThreads );
		for ( int i = 0; i < threads.length; i++ )
		{
			int finalI = i;
			threads[ i ] = new Thread( "ZSurface Reslice thread " + finalI)
			{
				@Override
				public void run()
				{
					IntervalView< T > excerptIntervalView = createIntervaView(excerpt, offset[finalI], unit);
					RandomAccess< T > randomAccess = excerptIntervalView.randomAccess();
					Cursor< T > excerpt_cursor = excerptIntervalView.cursor();

					IntervalView< T > intervalView = createIntervaView(input, inputOffset[finalI], unit);
					RealRandomAccess< T > inputx_Real = Views.interpolate( Views.extendBorder( intervalView ), NLinterp_factory ).realRandomAccess();

					IntervalView< U > depthMapIntervalView = createIntervaView(depthMap, depthMapOffset[finalI], unit);
					RandomAccess< U > depthMapx = depthMapIntervalView.randomAccess();

					float z_map;
					int[] tmp_pos = new int[nDim];

					while(excerpt_cursor.hasNext())
					{
						excerpt_cursor.fwd();
						excerpt_cursor.localize(tmp_pos);
						depthMapx.setPosition(new int[] {tmp_pos[0],tmp_pos[1]});
						z_map =  depthMapx.get().getRealFloat();

						inputx_Real.setPosition(new float[] {(float)tmp_pos[0],(float)tmp_pos[1], (float)(tmp_pos[2]-(sliceOnTop)) + z_map });
						randomAccess.setPosition(excerpt_cursor);
						randomAccess.get().set( inputx_Real.get() );
					}
				}
			};
		}

		SimpleMultiThreading.startAndJoin( threads );
		return excerpt;
	}

	static <T> IntervalView<T> createIntervaView(Img<T> img, long[] offset, long unit) {
		long[] max = new long[img.numDimensions()];
		img.dimensions(max);
		max[1] = unit;

		return Views.offsetInterval(img, offset, max);
	}

	static long[][] createOffset(Img img, int numThreads) {
		final long[][] offset = new long[ numThreads ][img.numDimensions()];

		for ( int d = 0; d < offset.length; d++ )
		{
			offset[d] = new long[img.numDimensions()];

			for (int i = 0; i < offset[d].length; i++) {
				offset[d][i] = 0;
			}
			// height
			offset[d][1] = img.dimension( 1 ) / numThreads * d;
		}
		return offset;
	}


	/**
	 * 
	 * @param input a 3D image
	 * @param depthmap a 2D image with same xy dimension as input where pixel value represent altitude, z, (in pixel) in input
	 * @param sliceOnTop number of slices on top of the surface defined by the depth map in the output image
	 * @param sliceBelow number of slices below the surface defined by the depth map in the output image
	 * @return
	 */
	public static <T extends NumericType<T> & NativeType<T> , U extends RealType<U> >
	Img<T> ZSurface_reslice(Img<T> input, Img<U> depthMap, int sliceOnTop, int sliceBelow)
	{
		RandomAccess< U > depthMapx = Views.extendBorder( depthMap ).randomAccess();
		RandomAccess< T > inputx = Views.extendBorder( input ).randomAccess();
		
		int nDim = input.numDimensions();
		long[] dims = new long[nDim];
		input.dimensions(dims);
		long output_height = sliceOnTop + sliceBelow + 1;
		
		final ImgFactory< T > imgFactory = new ArrayImgFactory< T >();
		final Img< T > excerpt = imgFactory.create( new long[] {dims[0],dims[1], output_height} , input.firstElement().createVariable() );
		Cursor< T > excerpt_cursor = excerpt.localizingCursor();

		
		int[] tmp_pos = new int[nDim];
		int z_map;
		while(excerpt_cursor.hasNext())
		{
			excerpt_cursor.fwd();
			excerpt_cursor.localize(tmp_pos);
			depthMapx.setPosition(new int[] {tmp_pos[0],tmp_pos[1]});
			z_map = (int) depthMapx.get().getRealFloat();
			
			inputx.setPosition(new int[] {tmp_pos[0],tmp_pos[1], tmp_pos[2]-(sliceOnTop) + z_map });
			excerpt_cursor.get().set( inputx.get() );
		}
		
		return excerpt;
	}
	
	
	

	
}
