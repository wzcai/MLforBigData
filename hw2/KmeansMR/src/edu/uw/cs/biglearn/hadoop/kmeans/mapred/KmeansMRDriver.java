package edu.uw.cs.biglearn.hadoop.kmeans.mapred;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;

import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TextInputFormat;

import edu.uw.cs.biglearn.hadoop.kmeans.Cluster;
import edu.uw.cs.biglearn.hadoop.kmeans.io.ClusterOutputFormat;

public class KmeansMRDriver {
	
	ArrayList<String> dictionary;
	int K;

	/**
	 * Creates a driver running kmeans with given number of clusters.
	 * @param numClusters
	 */
	public KmeansMRDriver(int numClusters) {
		K = numClusters;
	}
	
	/**
	 * Run an iteration of Kmeans. Take tfidf input from inputpath, take the current cluster centers from cachepath, and
	 * output the new cluster centers and within cluster distance into outputpath. 
	 * @param inputpath
	 * @param outputpath
	 * @param cachepath
	 * @param iter
	 * @throws IOException
	 */
	public void runIter(Path inputpath, Path outputpath, Path cachepath, int iter) throws IOException {		 
		JobConf conf = new JobConf(KmeansMRDriver.class);
		conf.setJobName("Kmeans Map Reduce Iteration: " + iter);
			
		conf.setOutputKeyClass(IntWritable.class);
		conf.setOutputValueClass(Text.class);
			
		conf.setMapperClass(KmeansMapper.class);
		conf.setReducerClass(KmeansReducer.class);
	
		conf.setInputFormat(TextInputFormat.class);
		conf.setOutputFormat(ClusterOutputFormat.class);
	
		FileInputFormat.setInputPaths(conf, inputpath);
		FileOutputFormat.setOutputPath(conf, outputpath);
		
		FileSystem fs = FileSystem.get(conf);
		FileStatus[] status = fs.listStatus(cachepath);
		
		for (FileStatus s : status) {
			if (s.getPath().getName().startsWith("cluster")) {
				DistributedCache.addCacheFile(s.getPath().toUri(), conf);
				System.err.println("Adding " + s.getPath()+ " to distributed cache");
			}
		}
		conf.setInt("numClusters", K);
		JobClient.runJob(conf);
	}
	
	public static void main(String args[]) throws IOException {
		KmeansMRDriver driver = new KmeansMRDriver(20);
		/**
		 * NOTE: You may need to replace the path with your hdfs settings.
		 * May need to be "hdfs:///user/xxxx/kmeans/etc"
		 */
		Path dictionarypath = new Path("kmeans/dictionary.txt");
		Path inputpath = new Path("kmeans/tfidf.txt");
		
		driver.loadDictionary(dictionarypath);
		
		/**
		 * You need to run 5 iterations after you complete the code.
		 */
		int numiter = 5;
		for (int i = 1; i <= numiter; i++) {
			/**
			 * NOTE: You may need to replace the path with your hdfs settings.
			 */
			Path outputpath = new Path("kmeans/output/cluster"+i);
			Path cache = i==1 ? new Path("kmeans/cluster0/") : 
								new Path("kmeans/output/cluster"+(i-1));
			driver.runIter(inputpath, outputpath, cache, i);
			ArrayList<Cluster> clusters = driver.loadCluster(outputpath);
			driver.printClusters(clusters);
			ArrayList<String> distances = driver.loadDistance(outputpath);
			driver.printDistance(distances);
		}
	}

	/**
	 * Print out the total distance within each cluster.
	 * @param distances
	 */
	private void printDistance(ArrayList<String> distances) {
		System.out.println("=================== Squared distances within the clusters ================= ");
		StringBuilder builder = new StringBuilder();
		double totaldistance = 0.0;
		for (String s : distances) {
			String[] splits = s.split("\\|");
			builder.append("Cluster " + splits[0] + ": " + splits[1] + "\n");
			totaldistance += Double.parseDouble(splits[1]);
		}
		builder.append("Total squared distance: " + totaldistance);
		System.out.println(builder);
	}
	
	/**
	 * Print out the top 10 words from each cluster.
	 * @param clusters
	 */
	private void printClusters(ArrayList<Cluster> clusters) {
		System.out.println("=================== Top words in the clusters ================= ");
		for (Cluster c : clusters) {
			List<Entry<Integer, Double>> entries = new ArrayList<Entry<Integer, Double>>(c.tfidf.entrySet());
			// sort the entries by tfidf
			Collections.sort(entries, new Comparator<Entry<Integer, Double>> () {
				@Override
				public int compare(Entry<Integer, Double> arg0,
						Entry<Integer, Double> arg1) {
					if (arg0.getValue() < arg1.getValue())
						return 1;
					else
						return -1;
					}
				});
			// print the first 10 words
			StringBuilder builder = new StringBuilder("Cluster " + c.id + "| ");
			for (int i = 0; i < 10; i++) {
				builder.append(dictionary.get(entries.get(i).getKey()) + ":" + entries.get(i).getValue());
				if (i < 9) builder.append(", ");
			}
			System.out.println(builder);
			System.out.println("========================================================= ");
		}
	}
	
	/**
	 * Load the dictionary from hdfs path.
	 * @param path
	 * @throws IOException
	 */
	private void loadDictionary(Path path) throws IOException {
		FileSystem fs = FileSystem.get(new JobConf());
		System.err.println("Loading dictionary from: " +path.toString());
		BufferedReader reader = new BufferedReader(new InputStreamReader(fs.open(path)));
		String line;
		dictionary = new ArrayList<String>();
		while ((line = reader.readLine())!=null)
			dictionary.add(line.split(" ")[0]);
		System.out.println("Succeeded in loading dictionary.");
	}
	
	/**
	 * Load the cluster centers from hdfs path.
	 * @param path
	 * @throws IOException
	 */
	private ArrayList<Cluster> loadCluster(Path path) {
		ArrayList<Cluster> clusters = new ArrayList<Cluster>(K);
		try {
			FileSystem fs = FileSystem.get(new JobConf());
			FileStatus[] status = fs.listStatus(path);
			for (FileStatus s : status) {
				if (s.getPath().getName().startsWith("cluster")) {					
					BufferedReader reader = new BufferedReader(new InputStreamReader(fs.open(s.getPath())));
					String line = null;
					while ((line = reader.readLine()) != null) {
						Cluster c = new Cluster();
						c.read(line);
						clusters.add(c);
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return clusters;
	}

	/**
	 * Load the distance file from hdfs path.
	 * @param path
	 * @throws IOException
	 */
	private ArrayList<String> loadDistance(Path path) {
		ArrayList<String> distances = new ArrayList<String>(K);
		try {
			FileSystem fs = FileSystem.get(new JobConf());
			FileStatus[] status = fs.listStatus(path);
			for (FileStatus s : status) {
				if (s.getPath().getName().startsWith("distance")) {					
					BufferedReader reader = new BufferedReader(new InputStreamReader(fs.open(s.getPath())));
					String line = null;
					while ((line = reader.readLine()) != null) {
						distances.add(line);
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return distances;
	}
}
