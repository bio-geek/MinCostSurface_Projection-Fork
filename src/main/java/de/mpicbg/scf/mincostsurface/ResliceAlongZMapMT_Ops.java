package de.mpicbg.scf.mincostsurface;

import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.ops.AbstractOp;
import net.imagej.ops.Op;
import net.imagej.ops.OpService;
import net.imglib2.img.Img;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import org.scijava.ItemIO;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

/**
 * Author: HongKee Moon (moon@mpi-cbg.de), Scientific Computing Facility
 * Organization: MPI-CBG Dresden
 * Date: October 2019
 */
@Plugin(type = Op.class, name="zMapResliceMT", headless = true, label="zMapResliceMT", visible=true, menuPath = "Plugins>Z map reslice>Multi Threads")
public class ResliceAlongZMapMT_Ops < T extends RealType<T> & NativeType< T >, U extends RealType<U>> extends AbstractOp {


    @Parameter( label = "input image" )
    private Img input;

    @Parameter ( label = "z map" )
    private Img zMap;

    @Parameter ( label = "slice Above the z map" )
    private int sliceAbove;

    @Parameter ( label = "slice Below the z map" )
    private int sliceBelow;

    @Parameter ( label = "Number of Threads")
    private int numThreads;


    // output
    @Parameter  (type = ItemIO.OUTPUT)
    private Img<T> outputExcerpt;


    @Parameter
    OpService op;

    @Override
    public void run() {

        Img<T> input_img = (Img<T>) input;
        Img<U> zMap_img = (Img<U>) zMap;

        outputExcerpt = img_utils.ZSurface_reslice3(input_img, zMap_img, sliceAbove, sliceBelow, numThreads);
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
            ij.command().run(ResliceAlongZMap_Ops.class, true);
        }
    }



}



