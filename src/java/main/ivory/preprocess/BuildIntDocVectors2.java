/*
 * Ivory: A Hadoop toolkit for Web-scale information retrieval
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package ivory.preprocess;

import ivory.data.IntDocVector;
import ivory.data.LazyIntDocVector;
import ivory.data.TermDocVector;
import ivory.data.TermIdMapWithCache;
import ivory.tokenize.DocumentProcessingUtils2;
import ivory.util.Constants;
import ivory.util.RetrievalEnvironment;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.log4j.Logger;

import edu.umd.cloud9.util.PowerTool;

public class BuildIntDocVectors2 extends PowerTool {
	private static final Logger LOG = Logger.getLogger(BuildIntDocVectors2.class);

	protected static enum Docs { Skipped, Total }
	protected static enum MapTime { DecodingAndIdMapping, EncodingAndSpilling }

	private static class MyMapper extends Mapper<IntWritable, TermDocVector, IntWritable, IntDocVector> {
		private TermIdMapWithCache termidMap = null;
		private static final LazyIntDocVector docVector = new LazyIntDocVector();

		@Override
		public void setup(Mapper<IntWritable, TermDocVector, IntWritable, IntDocVector>.Context context) {
			try {
				Configuration conf = context.getConfiguration();
				FileSystem fs;
				try {
					fs = FileSystem.get(conf);
				} catch (IOException e) {
					throw new RuntimeException("Error opening the FileSystem!");
				}
				
				RetrievalEnvironment env;
				try {
					env = new RetrievalEnvironment(conf.get(Constants.IndexPath), fs);
				} catch (IOException e) {
					throw new RuntimeException("Unable to create RetrievalEnvironment!");
				}

				String termsFile = env.getIndexTermsData();
				String termidsFile = env.getIndexTermIdsData();
				String idToTermFile = env.getIndexTermIdMappingData();

				// We need to figure out which file in the DistributeCache is which...
				Map<String, Path> pathMapping = new HashMap<String, Path>();
				Path[] localFiles = DistributedCache.getLocalCacheFiles(context.getConfiguration());
				for ( Path p : localFiles) {
					LOG.info("In DistributedCache: " + p);
					if ( p.toString().contains(termsFile)) {
						pathMapping.put(termsFile, p);
					} else if ( p.toString().contains(termidsFile)) {
						pathMapping.put(termidsFile, p);
					} if ( p.toString().contains(idToTermFile)) {
						pathMapping.put(idToTermFile, p);
					}
				}

				LOG.info(" - terms: " + pathMapping.get(termsFile));
				LOG.info(" - id: " + pathMapping.get(termidsFile));
				LOG.info(" - idToTerms: " + pathMapping.get(idToTermFile));

				termidMap = new TermIdMapWithCache(pathMapping.get(termsFile), pathMapping.get(termidsFile), pathMapping.get(idToTermFile),
						0.3f, FileSystem.getLocal(context.getConfiguration()));
			} catch (Exception e) {
				e.printStackTrace();
				throw new RuntimeException("Error initializing DocnoMapping!");
			}
		}

		@Override
		public void map(IntWritable key, TermDocVector doc, Context context) throws IOException, InterruptedException {
			long startTime = System.currentTimeMillis();
			TreeMap<Integer, int[]> positions = DocumentProcessingUtils2.getTermIDsPositionsMap(doc, termidMap);
			context.getCounter(MapTime.DecodingAndIdMapping).increment(System.currentTimeMillis() - startTime);

			startTime = System.currentTimeMillis();
			docVector.setTermPositionsMap(positions);
			context.write(key, docVector);
			context.getCounter(MapTime.EncodingAndSpilling).increment(System.currentTimeMillis() - startTime);
			context.getCounter(Docs.Total).increment(1);
		}
	}

	public static final String[] RequiredParameters = { Constants.IndexPath };

	public String[] getRequiredParameters() {
		return RequiredParameters;
	}

	public BuildIntDocVectors2(Configuration conf) {
		super(conf);
	}

	public int runTool() throws Exception {
		Configuration conf = getConf();
		FileSystem fs = FileSystem.get(conf);

		String indexPath = conf.get(Constants.IndexPath);
		RetrievalEnvironment env = new RetrievalEnvironment(indexPath, fs);
		String collectionName = env.readCollectionName();

		LOG.info("PowerTool: BuildIntDocVectors2");
		LOG.info(String.format(" - %s: %s", Constants.CollectionName, collectionName));
		LOG.info(String.format(" - %s: %s", Constants.IndexPath, indexPath));

		String termsFile = env.getIndexTermsData();
		String termIDsFile = env.getIndexTermIdsData();
		String idToTermFile = env.getIndexTermIdMappingData();

		Path termsFilePath = new Path(termsFile);
		Path termIDsFilePath = new Path(termIDsFile);

		if (!fs.exists(termsFilePath) || !fs.exists(termIDsFilePath)) {
			LOG.error("Error, terms files don't exist!");
			return 0;
		}

		Path outputPath = new Path(env.getIntDocVectorsDirectory());
		if (fs.exists(outputPath)) {
			LOG.info("IntDocVectors already exist: skipping!");
			return 0;
		}

		DistributedCache.addCacheFile(new URI(termsFile), conf);
		DistributedCache.addCacheFile(new URI(termIDsFile), conf);
		DistributedCache.addCacheFile(new URI(idToTermFile), conf);

		conf.set("mapred.child.java.opts", "-Xmx2048m");
		//conf.set("mapreduce.map.java.opts", "-Xmx2048m");

		Job job = new Job(conf, "BuildIntDocVectors2:" + collectionName);
		job.setJarByClass(BuildIntDocVectors2.class);

		job.setNumReduceTasks(0);

		job.setInputFormatClass(SequenceFileInputFormat.class);
		job.setOutputFormatClass(SequenceFileOutputFormat.class);

		FileInputFormat.setInputPaths(job, env.getTermDocVectorsDirectory());
		FileOutputFormat.setOutputPath(job, outputPath);
		SequenceFileOutputFormat.setOutputCompressionType(job, SequenceFile.CompressionType.RECORD);

		job.setMapOutputKeyClass(IntWritable.class);
		job.setMapOutputValueClass(LazyIntDocVector.class);
		job.setOutputKeyClass(IntWritable.class);
		job.setOutputValueClass(LazyIntDocVector.class);

		job.setMapperClass(MyMapper.class);

		long startTime = System.currentTimeMillis();
		job.waitForCompletion(true);
		LOG.info("Job Finished in " + (System.currentTimeMillis() - startTime) / 1000.0	+ " seconds");

		return 0;
	}
}