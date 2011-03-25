/*
 * LensKit, a reference implementation of recommender algorithms.
 * Copyright 2010-2011 Regents of the University of Minnesota
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.grouplens.lenskit.eval.maven;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedList;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.grouplens.lenskit.data.dao.RatingDataAccessObject;
import org.grouplens.lenskit.data.dao.SimpleFileDAO;
import org.grouplens.lenskit.eval.AlgorithmInstance;
import org.grouplens.lenskit.eval.InvalidRecommenderException;
import org.grouplens.lenskit.eval.crossfold.CrossfoldEvaluator;
import org.grouplens.lenskit.eval.crossfold.RandomUserRatingProfileSplitter;
import org.grouplens.lenskit.eval.crossfold.TimestampUserRatingProfileSplitter;
import org.grouplens.lenskit.eval.crossfold.UserRatingProfileSplitter;

/**
 * Run a crossfold evaluation with LensKit.
 * 
 * @goal crossfold-eval
 * @execute phase="compile"
 * @requiresDependencyResolution runtime
 */
public class LenskitCrossfoldEvalMojo extends AbstractMojo {
    /**
     * The project.
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;
    
    /**
     * Location of the output file.
     * 
     * @parameter expression="${lenskit.outputFile}" default-value="${project.build.directory}/lenskit.csv"
     * @required
     */
    private File outputFile;
    
    /**
     * Location of the output class directory for this project's build.
     * @parameter expression="${project.build.outputDirectory}"
     */
    private File classDirectory;
    
    /**
     * Location of the recommender configuration script.
     * @parameter expression="${lenskit.recommenderScript}"
     * @required
     */
    private File recommenderScript;
    
    /**
     * Input data location.
     * @parameter expression="${lenskit.dataFile}"
     * @required
     */
    private File dataFile;
    
    /**
     * Input file delimiter.
     * @parameter expression="${lenskit.inputDelimiter}"
     */
    private String inputDelimiter = "\t";
    
    /**
     * Split mode.
     * @parameter expression="${lenskit.splitMode}" default-value="random"
     */
    private String splitMode;
    
    /**
     * Fold count.
     * @parameter expression="${lenskit.numFolds}" default-value="5"
     */
    private int numFolds;
    
    /**
     * Holdout fraction for test users.
     * @parameter expression="${lenskit.holdoutFraction}" default-value="0.333333"
     */
    private double holdoutFraction;

    public void execute() throws MojoExecutionException {
        // Before we can run, we need to replace our class loader to include
        // the project's output directory.  Kinda icky, but it's the brakes.
        // TODO: find a better way to set up our class loader
        URL outputUrl;
        try {
            outputUrl = classDirectory.toURI().toURL();
        } catch (MalformedURLException e1) {
            throw new MojoExecutionException("Cannot build URL for project output directory");
        }
        ClassLoader loader = new URLClassLoader(new URL[]{outputUrl}, getClass().getClassLoader());
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            MavenLoggerFactory.getInstance().setLog(getLog());
            UserRatingProfileSplitter splitter;
            if (splitMode.toLowerCase().equals("random"))
                splitter = new RandomUserRatingProfileSplitter(holdoutFraction);
            else if (splitMode.toLowerCase().equals("timestamp"))
                splitter = new TimestampUserRatingProfileSplitter(holdoutFraction);
            else
                throw new MojoExecutionException("Invalid split mode: " + splitMode);

            RatingDataAccessObject ratings;
            try {
                ratings = new SimpleFileDAO(dataFile, inputDelimiter);
            } catch (FileNotFoundException e1) {
                throw new MojoExecutionException("Input file " + dataFile + " not found", e1);
            }

            List<AlgorithmInstance> algorithms = new LinkedList<AlgorithmInstance>();
            try {
                algorithms.add(AlgorithmInstance.load(recommenderScript, loader));
            } catch (InvalidRecommenderException e) {
                throw new MojoExecutionException("Invalid recommender", e);
            }
            Writer output;
            try {
                output = new FileWriter(outputFile);
            } catch (IOException e) {
                throw new MojoExecutionException("Error opening output file " + outputFile, e);
            }
            CrossfoldEvaluator eval;
            try {
                eval = new CrossfoldEvaluator(ratings, algorithms, numFolds, splitter, output);
            } catch (IOException e) {
                throw new MojoExecutionException("Error loading evaluator.", e);
            }

            try {
                eval.run();
            } catch (Exception e) {
                throw new MojoExecutionException("Unexpected failure running recommender evaluation.", e);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }
    }
}
