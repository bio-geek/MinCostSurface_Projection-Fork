package de.mpicbg.scf.mincostsurface;

import java.util.ArrayList;
import java.util.List;

import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.outofbounds.OutOfBounds;
import net.imglib2.outofbounds.OutOfBoundsConstantValueFactory;
import net.imglib2.outofbounds.OutOfBoundsFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.ByteType;
import net.imglib2.type.numeric.real.FloatType;
import graphcut.GraphCut;
import graphcut.Terminal;

//import graphcut_algo.GraphCut;
//import graphcut_algo.Terminal;



	


/**
 * 
 * @author Benoit Lombardot, Scientific Computing facility (MPI-CBG)
 *
 * tentative 2D version of the MinCostZ surface
 * only one line for now
 */


public class MinCostYCurve < T extends RealType<T> & NumericType< T > & NativeType< T > >{


	private int n_surface;
	private long[] dimensions;
	private GraphCut graphCut_Solver;
	private float infiniteWeight = 1000000.0f;
	private float zeroWeight = 0.0f;
	private List<int[][]> graphs_edges;
	private List<float[][]> graphs_edges_weights;
	private List<float[][]> graphs_terminal_weights;
	private boolean isProcessed;
	private float maxFlow;
	
	
	
	public MinCostYCurve()
	{
		n_surface = 0;
		graphs_edges= new ArrayList<int[][]>();
		graphs_edges_weights = new ArrayList<float[][]>();
		graphs_terminal_weights = new ArrayList<float[][]>();
		isProcessed = false;
		maxFlow = 0 ;
		
		
	}
	
	
	
	/**
	 * @return the number of surface graph build so far
	 */
	public int getNSurfaces(){ return n_surface;}

	
	
	/**
	 * @return the result of the maxflow computation. it returns 0 before the process() method is used
	 */
	public float getMaxFlow(){ return maxFlow;}


	/**
	 * This methods solve the maxFlow problem for the surfaces defined and the inter-surface constraints
	 *  
	 * @return
	 */
	public boolean Process()
	{
		if( n_surface<=0)
			return false;
		
		
		// determine the number of nodes (except terminal nodes)
		int nNodes = (int)(n_surface*dimensions[0]*dimensions[1]);
		
		// determine the number of edges in the graph (except edges from or to terminals)
		int nEdges = 0;
		for(int i=0; i<graphs_edges.size(); i++)
			nEdges += graphs_edges.get(i)[0].length;
				
		// instanciate the solver
		graphCut_Solver = new GraphCut( nNodes, nEdges );
		
		// feed the graphcut solver with the surface graphs and surfaces constraints
		for(int i=0; i<graphs_edges.size(); i++)
		{
			int[][] edges = graphs_edges.get(i);
			float[][] ws = graphs_edges_weights.get(i);
			for( int j=0; j<edges[0].length; j++) 
				graphCut_Solver.setEdgeWeight(edges[0][j], edges[1][j], ws[0][j], ws[1][j]);
		}
		
		for(int i=0; i<graphs_terminal_weights.size(); i++)
		{
			float[][] tw = graphs_terminal_weights.get(i);
			for( int j=0; j<tw[0].length; j++)
				graphCut_Solver.setTerminalWeights(j+i*nNodes/n_surface, tw[0][j], tw[1][j]);	
		}
		
		// Solve the mincut maxflow problem
		maxFlow = graphCut_Solver.computeMaximumFlow(false, null);
		
		isProcessed = true;
		
		return true;
	}
	
	
	
	public boolean Create_Surface_Graph(Img<T> image_cost, int max_dy)
	{
		float factor = 1f;
		return Create_Surface_Graph( image_cost, max_dy, factor);
	}
	
	
	/**
	 * This method build the graph to detect a minimum cost surface in a cost volumes and with a constraints on altitude variation
	 * 
	 * @param image_cost cost function 
	 * @param max_dz maximum altitude variation between 2 pixels
	 * @param factor positive multiplicative value to make intensity in both surfaces look similar
	 */
	public boolean Create_Surface_Graph(Img<T> image_cost, int max_dy, float factor)
	{
		/////////////////////////////////////////////
		// Check input validity /////////////////////
		boolean isOk=true;
		
		int nDim = image_cost.numDimensions(); 
		long[] dims = new long[nDim];
		image_cost.dimensions(dims);
		
		if( nDim !=2)
			isOk=false;
		if (dimensions == null & isOk)
			dimensions = dims;
		else // check that the dimensions of the new image function is consistent with earlier one
		{
			for(int i=0; i<nDim; i++)
				if(dimensions[i]!=dims[i])
					isOk= false;
		}
		if( max_dy<0 )
			isOk= false;
		
		if(!isOk)
			return false;
		
		////////////////////////////////////////////////////////////////////////////////////////////
		// define surface graph edges using image_cost for the weights /////////////////////////////
		
			
		long width = dimensions[0];
		long height = dimensions[0];
		
		//long Slice = dimensions[0]*dimensions[1];
		long nNodes_perSurf = dimensions[0]*dimensions[1];
		long nEdges = width * (height-1)  +  2 * (width-1) * (height-max_dy);

		int[][] Edges = new int[2][];
		for(int i=0; i<2; i++){  Edges[i] = new int[(int)nEdges];  }
		float[][] Edges_weights = new float[2][];
		for(int i=0; i<2; i++){  Edges_weights[i] = new float[(int)nEdges];  }
		float[][] Terminal_weights = new float[2][];
		for(int i=0; i<2; i++){  Terminal_weights[i] = new float[(int)nNodes_perSurf];  }
		
		int EdgeCount = 0;
		
		
		// defining the neighborhood //////////////////////////////////////////////////////////////
		
		// neighbor definition for planes z>0
		int nNeigh = 3;
		int[][] neigh_pos_to_current = new int[nNeigh][];
		neigh_pos_to_current[0] = new int[] {-1, -max_dy};
		neigh_pos_to_current[1] = new int[] { 1, -max_dy};
		neigh_pos_to_current[2] = new int[] { 0, -1};
		
		long[] neigh_offset_to_current = new long[nNeigh];
		for(int i = 0; i<nNeigh; i++)
			neigh_offset_to_current[i] = neigh_pos_to_current[i][0] + neigh_pos_to_current[i][1] * width ;				                     
		
		int[][] neigh_pos_to_previous = new int[nNeigh][];
		neigh_pos_to_previous[0] = neigh_pos_to_current[0]; 
		for(int i = 1; i<nNeigh; i++)
		{	neigh_pos_to_previous[i] = new int[nDim];
			for (int j = 0; j<nDim; j++)
				neigh_pos_to_previous[i][j] =  neigh_pos_to_current[i][j] -  neigh_pos_to_current[i-1][j];
		}	
		
		// defining a factory to test out of bound conditions
		T outOfBoundValue = image_cost.firstElement();
		outOfBoundValue.setZero();
		final OutOfBoundsFactory< T, RandomAccessibleInterval< T >> oobImageFactory =  new OutOfBoundsConstantValueFactory< T, RandomAccessibleInterval< T >>( outOfBoundValue );
		final OutOfBounds< T > imagex = oobImageFactory.create( image_cost );
		
		
		// iterator over the image pixels
		Cursor<T> image_cursor = image_cost.cursor();
		int[] position = new int[] {0,0,0};
		long current_offset;
		float w=0;
		
		
		image_cursor.reset();
		while ( image_cursor.hasNext() )
        {	
			image_cursor.fwd();
			w = factor * image_cursor.get().getRealFloat();
			image_cursor.localize(position);
			long posIdx = position[0]+ position[1]*width;
			current_offset = (n_surface)*nNodes_perSurf + posIdx;
			imagex.setPosition(position);
			
			if (position[1]>max_dy)
			{	for(int i =0; i<nNeigh; i++)
				{
					imagex.move(neigh_pos_to_previous[i]);
					// go to the next neighbor if the current neighbor is out of bound
					if (imagex.isOutOfBounds()){ continue;} 
					// else set a new edge
					//graphCut_Solver.setEdgeWeight( (int) current_offset, (int)current_offset + (int)neigh_offset_to_current[i], infiniteWeight, zeroWeight );
					Edges[0][EdgeCount] = (int)current_offset;
					Edges[1][EdgeCount] = (int)current_offset + (int)neigh_offset_to_current[i];
					Edges_weights[0][EdgeCount] = infiniteWeight;
					Edges_weights[1][EdgeCount] = zeroWeight;
					EdgeCount++;
				}
				w -= factor * imagex.get().getRealFloat();
			}
			else if (position[1]>0)
			{	
				//graphCut_Solver.setEdgeWeight( (int)current_offset, (int)current_offset - (int)Slice, infiniteWeight, zeroWeight );
				Edges[0][EdgeCount] = (int)current_offset;
				Edges[1][EdgeCount] = (int)current_offset - (int)width;
				Edges_weights[0][EdgeCount] = infiniteWeight;
				Edges_weights[1][EdgeCount] = zeroWeight;
				EdgeCount++;
				
				imagex.move(new int[] {0,-1}); // no need to test for out of bound here
				w -= factor * imagex.get().getRealFloat();
			}
			else
				w = -infiniteWeight;
			
			// set edges to source and sink
			if (w<0)
			{
				Terminal_weights[0][(int)posIdx] = -w; 
				Terminal_weights[1][(int)posIdx] = zeroWeight; 
				//graphCut_Solver.setTerminalWeights( (int)current_offset, -w, zeroWeight); // as far as I understand set a link from source to current pixel
			}
			else if (w>0)
			{
				Terminal_weights[0][(int)posIdx] = zeroWeight; 
				Terminal_weights[1][(int)posIdx] = w; 
				//graphCut_Solver.setTerminalWeights( (int)current_offset, zeroWeight, w);
			}
			
        }
		
		
		graphs_edges.add(Edges);
		graphs_edges_weights.add(Edges_weights);
		graphs_terminal_weights.add(Terminal_weights);
		// increment the number of surface set and return success of the operation
		n_surface++;

		
		return true;
	}
	
	
	
	
	/*
	
	
	/*
	 * This method create a graph defining the relation between the 2 surfaces
	 * in particular it imposes surface 1 to be on top. Surface can't cross each other and
	 * surface 2 distance to surface 1 will be in the range min_dist to max_dist. 
	 * 
	 * @param surf1 the id of the first surface to interconnect (id starts at 1 and depends on Surface graph order of creation)	
	 * @param surf2 the id of the second surface to interconnect
	 * @param min_dist the minimum distance between the surface (in pixel)
	 * @param max_dist the maximum distance between the surface (in pixel)
	 */
	/*
	public boolean Add_NoCrossing_Constraint_Between_Surfaces(int surf1, int surf2, int min_dist, int max_dist)
	{
		
		// Check that surfaces have been defined and min/max_dist have consistent values
		if( surf1>n_surface | surf2>n_surface | surf2==surf1 | surf1<=0 | surf2<=0 | min_dist>max_dist | min_dist<0 )
			return false;
		
		
		//
		long Slice = dimensions[0]*dimensions[1];
		long nNodes_perSurf = dimensions[0]*dimensions[1]*dimensions[2];
		long nEdges =  ( (dimensions[2]-min_dist) + (dimensions[2]-max_dist) ) * Slice;
		
		int[][] Edges = new int[2][];
		for(int i=0; i<2; i++){  Edges[i] = new int[(int)nEdges];  }
		float[][] Edges_weights = new float[2][];
		for(int i=0; i<2; i++){  Edges_weights[i] = new float[(int)nEdges];  }
		
		int EdgeCount=0;
		
		
		int idx1, idx2;
		if (max_dist==min_dist)
		{
			for(int idx = 0; idx<nNodes_perSurf; idx++) 
			{	
				int z = (int)(idx/Slice);
				if (z > max_dist)
				{	
					idx1 = (int) ((surf1-1)*nNodes_perSurf + idx) ;
					idx2 = (int) ((surf2-1)*nNodes_perSurf + idx - max_dist * (int)Slice) ;
					Edges[0][EdgeCount] = idx1;
					Edges[1][EdgeCount] = idx2;
					Edges_weights[0][EdgeCount] = infiniteWeight;
					Edges_weights[1][EdgeCount] = infiniteWeight;
					EdgeCount++;
					//graphCut_Solver.setEdgeWeight( idx1, idx2, infiniteWeight);
				}
			}
		}
		else
		{
			for(int idx=0; idx<nNodes_perSurf; idx++) 
			{	
				idx1 = (int)( (surf1-1)*nNodes_perSurf + idx);
				idx2 = (int)( (surf2-1)*nNodes_perSurf + idx);
				int z = (int)(idx/Slice);

				if ( z > max_dist)
				{	
					Edges[0][EdgeCount] = idx1;
					Edges[1][EdgeCount] = idx2 - max_dist * (int)Slice;
					Edges_weights[0][EdgeCount] = infiniteWeight;
					Edges_weights[1][EdgeCount] = zeroWeight;
					EdgeCount++;
					//graphCut_Solver.setEdgeWeight( idx1, idx2 - max_dist*(int)Slice, infiniteWeight, zeroWeight );
				}
				if ( z < ( dimensions[2]-min_dist ))
				{	
					Edges[0][EdgeCount] = idx2;
					Edges[1][EdgeCount] = idx1 + min_dist * (int)Slice;
					Edges_weights[0][EdgeCount] = infiniteWeight;
					Edges_weights[1][EdgeCount] = zeroWeight;
					EdgeCount++;
					//graphCut_Solver.setEdgeWeight( idx2, idx1 + min_dist*(int)Slice, infiniteWeight, zeroWeight );	
				}
				
			}
		}
		
		graphs_edges.add(Edges);
		graphs_edges_weights.add(Edges_weights);
		
		return true;
	}

	*/
	
	// inter-surface edges for intersecting surfaces
	/*
	 * 
	 * @param surf1 	the id of the first surface to interconnect (id starts at 1 and depends on Surface graph order of creation)	
	 * @param surf2 	the id of the second surface to interconnect
	 * @param max_up 	the maximum distance of surface 2 on top of surface 1 (in pixel)
	 * @param max_down	the maximum distance of surface 2 below surface 1 (in pixel)
	 * @return a boolean indicating that the graph was built 
	 */
	/*
	public boolean Add_Crossing_Constraint_Between_Surfaces(int surf1, int surf2, int max_up, int max_down)
	{
		
		// Check that surfaces have been defined and min/max_dist have consistent values
		if( surf1>n_surface | surf2>n_surface | surf2==surf1 | surf1<=0 | surf2<=0 | max_up<0 | max_down<0 )
			return false;
		
		long Slice = dimensions[0]*dimensions[1];
		long nNodes_perSurf = dimensions[0]*dimensions[1]*dimensions[2];
		long nEdges =  ( (dimensions[2]-max_up) + (dimensions[2]-max_down) ) * Slice;
		
		int[][] Edges = new int[2][];
		for(int i=0; i<2; i++){  Edges[i] = new int[(int)nEdges];  }
		float[][] Edges_weights = new float[2][];
		for(int i=0; i<2; i++){  Edges_weights[i] = new float[(int)nEdges];  }
		
		int EdgeCount=0;
		
		
		int idx1, idx2;
		
		for(int idx=0; idx<nNodes_perSurf; idx++) 
		{	
			idx1 = (int)( (surf1-1)*nNodes_perSurf + idx);
			idx2 = (int)( (surf2-1)*nNodes_perSurf + idx);
			int z = (int)(idx/Slice);

			if ( z > max_up)
			{	
				Edges[0][EdgeCount] = idx1;
				Edges[1][EdgeCount] = idx2 - max_up * (int)Slice;
				Edges_weights[0][EdgeCount] = infiniteWeight;
				Edges_weights[1][EdgeCount] = zeroWeight;
				EdgeCount++;
				//graphCut_Solver.setEdgeWeight( idx1, idx2, infiniteWeight, zeroWeight );
			}
			if ( z < max_down )
			{	
				Edges[0][EdgeCount] = idx2;
				Edges[1][EdgeCount] = idx1 - max_down * (int)Slice;
				Edges_weights[0][EdgeCount] = infiniteWeight;
				Edges_weights[1][EdgeCount] = zeroWeight;
				EdgeCount++;
				//graphCut_Solver.setEdgeWeight( idx2, idx1+ min_dist * (int)Slice, infiniteWeight, zeroWeight );	
			}
		}
		
		
		graphs_edges.add(Edges);
		graphs_edges_weights.add(Edges_weights);
		
		return true;
		
	}
	
	*/
	
	
	///////////////////////////////////////////////////////////////////////////////////////////
	// create outputs  ////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////
	
		
	/**
	 * This methods produces a depth map for the surface with id Surf_Id
	 * A depth map is a 2D image where pixels values indicate the altitude of the surface
	 * the method will return null if the surface id is invalid or if the maxflow is not calculated
	 * 
	 * @param Surf_Id the id of the surface mask to output (id starts at 1 and are numbered after the order the surface were set) 
	 * @return a depthmap, i.e. a 2D image where pixel values indicate the surface altitude.  
	*/
	public Img<FloatType> get_Altitude_Map(int Surf_Id)
	{
		if( Surf_Id>n_surface | Surf_Id<=0 | !isProcessed )
			return null;

		
		long width = dimensions[0];
		long height = dimensions[1];
		int nNodes = (int)(width*height);
		
		final ImgFactory< FloatType > imgFactory2 = new ArrayImgFactory< FloatType >();
		final Img< FloatType > depth_map = imgFactory2.create( new long[] {dimensions[0]} , new FloatType() );
		RandomAccess< FloatType> depth_mapRA = depth_map.randomAccess();
		
		long[] position = new long[2];
		for (int idx = 0; idx<nNodes; idx++)
		{
			position[0] = idx % width ;
			depth_mapRA.setPosition(new long[] {position[0]} );
			
			if (graphCut_Solver.getTerminal(idx+nNodes*(Surf_Id-1)) == Terminal.FOREGROUND)
				depth_mapRA.get().add( new FloatType(1.0f) );
		}

		return depth_map;
	}
	
	
	
	/**
	 * This methods produce a binary volume where pixel on top (bottom) the surface are 0 (255)
	 * for the surface with id Surf_Id.
	 * It will return null if the surface id is invalid or if the maxflow is not calculated
	 * 
	 * @param Surf_Id the id of the surface mask to output (id starts at 1 and are numbered after the order the surface were set) 
	 * @return a 3D volume with same size as the cost images 
	 */
	public Img<ByteType> get_Surface_Mask(int Surf_Id)
	{
		if( Surf_Id>n_surface | Surf_Id<=0 | !isProcessed )
			return null;
		
		long width = dimensions[0];
		long height = dimensions[1];
		int nNodes = (int)(width*height);
		
		final ImgFactory< ByteType > imgFactory = new ArrayImgFactory< ByteType >();
		final Img< ByteType > segmentation = imgFactory.create( dimensions , new ByteType() );
		Cursor<ByteType> seg_cursor = segmentation.cursor();
		
		long[] position = new long[dimensions.length];
		long idx; 
		while (seg_cursor.hasNext())
		{
			seg_cursor.fwd();
			seg_cursor.localize(position);
			idx = position[0]+ position[1]*width ;
			
			if (graphCut_Solver.getTerminal( (int)(idx+nNodes*(Surf_Id-1)) ) == Terminal.FOREGROUND)
				seg_cursor.get().set((byte)255);
			else 
				seg_cursor.get().set( (byte)0 );
		}
		
		return segmentation;
	}
	
	
	

}
