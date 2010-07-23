package org.apache.nutch.fetcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Random;
import java.util.StringTokenizer;

import org.apache.avro.util.Utf8;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.nutch.crawl.GeneratorJob;
import org.apache.nutch.metadata.Nutch;
import org.apache.nutch.parse.ParserJob;
import org.apache.nutch.protocol.ProtocolFactory;
import org.apache.nutch.storage.Mark;
import org.apache.nutch.storage.StorageUtils;
import org.apache.nutch.storage.WebPage;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.NutchJob;
import org.apache.nutch.util.TableUtil;
import org.gora.mapreduce.GoraMapper;

/**
 * Multi-threaded fetcher.
 *
 */
public class FetcherJob implements Tool {

  public static final String PROTOCOL_REDIR = "protocol";

  public static final int PERM_REFRESH_TIME = 5;

  public static final Utf8 REDIRECT_DISCOVERED = new Utf8("___rdrdsc__");
  
  public static final String RESUME_KEY = "fetcher.job.resume";
  public static final String PARSE_KEY = "fetcher.parse";
  public static final String THREADS_KEY = "fetcher.threads.fetch";

  private static final Collection<WebPage.Field> FIELDS = new HashSet<WebPage.Field>();

  static {
    FIELDS.add(WebPage.Field.MARKERS);
    FIELDS.add(WebPage.Field.REPR_URL);
  }

  /**
   * <p>
   * Mapper class for Fetcher.
   * </p>
   * <p>
   * This class reads the random integer written by {@link GeneratorJob} as its key
   * while outputting the actual key and value arguments through a
   * {@link FetchEntry} instance.
   * </p>
   * <p>
   * This approach (combined with the use of {@link PartitionUrlByHost}) makes
   * sure that Fetcher is still polite while also randomizing the key order. If
   * one host has a huge number of URLs in your table while other hosts have
   * not, {@link FetcherReducer} will not be stuck on one host but process URLs
   * from other hosts as well.
   * </p>
   */
  public static class FetcherMapper
  extends GoraMapper<String, WebPage, IntWritable, FetchEntry> {

    private boolean shouldContinue;

    private Utf8 crawlId;

    private Random random = new Random();

    @Override
    protected void setup(Context context) {
      Configuration conf = context.getConfiguration();
      shouldContinue = conf.getBoolean("job.continue", false);
      crawlId = new Utf8(conf.get(GeneratorJob.CRAWL_ID, Nutch.ALL_CRAWL_ID_STR));
    }

    @Override
    protected void map(String key, WebPage page, Context context)
        throws IOException, InterruptedException {
      Utf8 mark = Mark.GENERATE_MARK.checkMark(page);
      if (!NutchJob.shouldProcess(mark, crawlId)) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Skipping " + TableUtil.unreverseUrl(key) + "; different crawl id");
        }
        return;
      }
      if (shouldContinue && Mark.FETCH_MARK.checkMark(page) != null) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Skipping " + TableUtil.unreverseUrl(key) + "; already fetched");
        }
        return;
      }
      context.write(new IntWritable(random.nextInt(65536)), new FetchEntry(context
          .getConfiguration(), key, page));
    }
  }

  public static final Log LOG = LogFactory.getLog(FetcherJob.class);

  private Configuration conf;
  
  public FetcherJob() {
    
  }
  
  public FetcherJob(Configuration conf) {
    setConf(conf);
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  public Collection<WebPage.Field> getFields(Job job) {
    Collection<WebPage.Field> fields = new HashSet<WebPage.Field>(FIELDS);
    if (job.getConfiguration().getBoolean(PARSE_KEY, true)) {
      ParserJob parserJob = new ParserJob();
      fields.addAll(parserJob.getFields(job));
    }
    ProtocolFactory protocolFactory = new ProtocolFactory(job.getConfiguration());
    fields.addAll(protocolFactory.getFields());

    return fields;
  }

  public  void fetch(int threads, String crawlId, boolean shouldResume, boolean isParsing)
      throws Exception {
    LOG.info("FetcherJob: starting");

    checkConfiguration();

    if (threads > 0) {
      getConf().setInt(THREADS_KEY, threads);
    }
    getConf().set(GeneratorJob.CRAWL_ID, crawlId);
    getConf().setBoolean(PARSE_KEY, isParsing);
    getConf().setBoolean(RESUME_KEY, shouldResume);
    
    // set the actual time for the timelimit relative
    // to the beginning of the whole job and not of a specific task
    // otherwise it keeps trying again if a task fails
    long timelimit = getConf().getLong("fetcher.timelimit.mins", -1);
    if (timelimit != -1) {
      timelimit = System.currentTimeMillis() + (timelimit * 60 * 1000);
      getConf().setLong("fetcher.timelimit", timelimit);
    }

    LOG.info("FetcherJob : timelimit set for : " + timelimit);
    LOG.info("FetcherJob: threads: " + getConf().getInt(THREADS_KEY, 10));
    LOG.info("FetcherJob: parsing: " + getConf().getBoolean(PARSE_KEY, true));
    LOG.info("FetcherJob: resuming: " + getConf().getBoolean(RESUME_KEY, false));
    if (crawlId.equals(Nutch.ALL_CRAWL_ID_STR)) {
      LOG.info("FetcherJob: fetching all");
    } else {
      LOG.info("FetcherJob: crawlId: " + crawlId);
    }

    Job job = new NutchJob(getConf(), "fetch");

    Collection<WebPage.Field> fields = getFields(job);
    StorageUtils.initMapperJob(job, fields, IntWritable.class,
        FetchEntry.class, FetcherMapper.class, PartitionUrlByHost.class, false);
    StorageUtils.initReducerJob(job, FetcherReducer.class);

    job.waitForCompletion(true);

    LOG.info("FetcherJob: done");
  }

  void checkConfiguration() {

    // ensure that a value has been set for the agent name and that that
    // agent name is the first value in the agents we advertise for robot
    // rules parsing
    String agentName = getConf().get("http.agent.name");
    if (agentName == null || agentName.trim().length() == 0) {
      String message = "Fetcher: No agents listed in 'http.agent.name'"
          + " property.";
      if (LOG.isFatalEnabled()) {
        LOG.fatal(message);
      }
      throw new IllegalArgumentException(message);
    } else {

      // get all of the agents that we advertise
      String agentNames = getConf().get("http.robots.agents");
      StringTokenizer tok = new StringTokenizer(agentNames, ",");
      ArrayList<String> agents = new ArrayList<String>();
      while (tok.hasMoreTokens()) {
        agents.add(tok.nextToken().trim());
      }

      // if the first one is not equal to our agent name, log fatal and throw
      // an exception
      if (!(agents.get(0)).equalsIgnoreCase(agentName)) {
        String message = "Fetcher: Your 'http.agent.name' value should be "
            + "listed first in 'http.robots.agents' property.";
        LOG.warn(message);
      }
    }
  }

  @Override
  public int run(String[] args) throws Exception {
    int threads = -1;
    boolean shouldResume = false;
    boolean isParsing = true;
    String crawlId;

    String usage = "Usage: FetcherJob (<crawl id> | -all) [-threads N] [-noParsing] [-resume]";

    if (args.length == 0) {
      System.err.println(usage);
      return -1;
    }

    crawlId = args[0];
    if (crawlId.equals("-threads") || crawlId.equals("-resume") || crawlId.equals("-noParsing")) {
      System.err.println(usage);
      return -1;
    }
    for (int i = 1; i < args.length; i++) {
      if ("-threads".equals(args[i])) {
        // found -threads option
        threads = Integer.parseInt(args[++i]);
      } else if ("-resume".equals(args[i])) {
        shouldResume = true;
      } else if ("-noParsing".equals(args[i])) {
        isParsing = false;
      }
    }

    fetch(threads, crawlId, shouldResume, isParsing); // run the Fetcher

    return 0;
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(NutchConfiguration.create(), new FetcherJob(), args);
    System.exit(res);
  }
}