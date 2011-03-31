/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.mapred;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.mapred.IndexCache;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelFutureProgressListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.DefaultFileRegion;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.FileRegion;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.util.CharsetUtil;

import static org.jboss.netty.buffer.ChannelBuffers.*;
import static org.jboss.netty.handler.codec.http.HttpHeaders.*;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.*;
import static org.jboss.netty.handler.codec.http.HttpMethod.*;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.*;
import static org.jboss.netty.handler.codec.http.HttpVersion.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputByteBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.DataOutputByteBuffer;
import org.apache.hadoop.mapreduce.security.SecureShuffleUtils;
import org.apache.hadoop.mapreduce.task.reduce.ShuffleHeader;


import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.server.nodemanager.NMConfig;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.AuxServices;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.ApplicationLocalizer;
import org.apache.hadoop.yarn.service.AbstractService;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.mapreduce.security.token.JobTokenIdentifier;
import org.apache.hadoop.mapreduce.security.token.JobTokenSecretManager;

// DEBUG
import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.log4j.Level;

// TODO packaging
public class ShuffleHandler extends AbstractService 
    implements AuxServices.AuxiliaryService {

  private static final Log LOG = LogFactory.getLog(ShuffleHandler.class);
  static {
    //DEBUG
    ((Log4JLogger)LOG).getLogger().setLevel(Level.DEBUG);
  }

  private int port;
  private ChannelFactory selector;
  private final ChannelGroup accepted = new DefaultChannelGroup();

  public static final String MAPREDUCE_SHUFFLE_SERVICEID =
      "mapreduce.shuffle";

  private static final Map<String,String> userRsrc =
    new ConcurrentHashMap<String,String>();
  private static final JobTokenSecretManager secretManager =
    new JobTokenSecretManager();

  public static final String SHUFFLE_PORT = "mapreduce.shuffle.port";

  public ShuffleHandler() {
    super("httpshuffle");
  }

  @Override
  public void initApp(String user, ApplicationId appId, ByteBuffer secret) {
    // TODO these bytes should be versioned
    try {
      DataInputByteBuffer in = new DataInputByteBuffer();
      in.reset(secret);
      Token<JobTokenIdentifier> jt = new Token<JobTokenIdentifier>();
      jt.readFields(in);
      // TODO: Once SHuffle is out of NM, this can use MR APIs
      JobID jobId = new JobID(Long.toString(appId.getClusterTimestamp()), appId.getId());
      userRsrc.put(jobId.toString(), user);
      LOG.info("Added token for " + jobId.toString());
      secretManager.addTokenForJob(jobId.toString(), jt);
    } catch (IOException e) {
      LOG.error("Error during initApp", e);
      // TODO add API to AuxiliaryServices to report failures
    }
  }

  @Override
  public void stopApp(ApplicationId appId) {
    JobID jobId = new JobID(Long.toString(appId.getClusterTimestamp()), appId.getId());
    secretManager.removeTokenForJob(jobId.toString());
  }

  @Override
  public synchronized void init(Configuration conf) {
    selector = new NioServerSocketChannelFactory(
        Executors.newCachedThreadPool(), Executors.newCachedThreadPool());
    super.init(new Configuration(conf));
  }

  // TODO change AbstractService to throw InterruptedException
  @Override
  public synchronized void start() {
    Configuration conf = getConfig();
    ServerBootstrap bootstrap = new ServerBootstrap(selector);
    bootstrap.setPipelineFactory(new HttpPipelineFactory(conf));
    int port = conf.getInt("mapreduce.shuffle.port", 8080);
    accepted.add(bootstrap.bind(new InetSocketAddress(port)));
    LOG.info(getName() + " listening on port " + port);
    super.start();
  }

  @Override
  public synchronized void stop() {
    accepted.close().awaitUninterruptibly(10, TimeUnit.SECONDS);
    ServerBootstrap bootstrap = new ServerBootstrap(selector);
    bootstrap.releaseExternalResources();
    super.stop();
  }

  public static class HttpPipelineFactory implements ChannelPipelineFactory {

    private final Shuffle SHUFFLE;

    public HttpPipelineFactory(Configuration conf) {
      SHUFFLE = new Shuffle(conf);
    }

    public ChannelPipeline getPipeline() throws Exception {
        return Channels.pipeline(
            new HttpRequestDecoder(),
            new HttpChunkAggregator(1 << 16),
            new HttpResponseEncoder(),
            new ChunkedWriteHandler(),
            SHUFFLE);
        // TODO factor security manager into pipeline
        // TODO factor out encode/decode to permit binary shuffle
        // TODO factor out decode of index to permit alt. models
    }

  }

  static class Shuffle extends SimpleChannelUpstreamHandler {

    private final Configuration conf;
    private final IndexCache indexCache;
    private final LocalDirAllocator lDirAlloc =
      new LocalDirAllocator(NMConfig.NM_LOCAL_DIR);

    public Shuffle(Configuration conf) {
      this.conf = conf;
      indexCache = new IndexCache(new JobConf(conf));
    }

    private static List<String> splitMaps(List<String> mapq) {
      if (null == mapq) {
        return null;
      }
      final List<String> ret = new ArrayList<String>();
      for (String s : mapq) {
        Collections.addAll(ret, s.split(","));
      }
      return ret;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent evt)
        throws Exception {
      HttpRequest request = (HttpRequest) evt.getMessage();
      if (request.getMethod() != GET) {
          sendError(ctx, METHOD_NOT_ALLOWED);
          return;
      }
      final Map<String,List<String>> q =
        new QueryStringDecoder(request.getUri()).getParameters();
      final List<String> mapIds = splitMaps(q.get("map"));
      final List<String> reduceQ = q.get("reduce");
      final List<String> jobQ = q.get("job");
      if (LOG.isDebugEnabled()) {
        LOG.debug("RECV: " + request.getUri() +
            "\n  mapId: " + mapIds +
            "\n  reduceId: " + reduceQ +
            "\n  jobId: " + jobQ);
      }

      if (mapIds == null || reduceQ == null || jobQ == null) {
        sendError(ctx, "Required param job, map and reduce", BAD_REQUEST);
        return;
      }
      if (reduceQ.size() != 1 || jobQ.size() != 1) {
        sendError(ctx, "Too many job/reduce parameters", BAD_REQUEST);
        return;
      }
      int reduceId;
      String jobId;
      try {
        reduceId = Integer.parseInt(reduceQ.get(0));
        jobId = jobQ.get(0);
      } catch (NumberFormatException e) {
        sendError(ctx, "Bad reduce parameter", BAD_REQUEST);
        return;
      } catch (IllegalArgumentException e) {
        sendError(ctx, "Bad job parameter", BAD_REQUEST);
        return;
      }
      final String reqUri = request.getUri();
      if (null == reqUri) {
        // TODO? add upstream?
        sendError(ctx, FORBIDDEN);
        return;
      }
      HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
      try {
        verifyRequest(jobId, ctx, request, response,
            new URL("http", "", 8080, reqUri));
      } catch (IOException e) {
        LOG.warn("Shuffle failure ", e);
        sendError(ctx, e.getMessage(), UNAUTHORIZED);
        return;
      }

      Channel ch = evt.getChannel();
      ch.write(response);
      // TODO refactor the following into the pipeline
      ChannelFuture lastMap = null;
      for (String mapId : mapIds) {
        try {
          lastMap =
            sendMapOutput(ctx, ch, userRsrc.get(jobId), jobId, mapId, reduceId);
          if (null == lastMap) {
            sendError(ctx, NOT_FOUND);
            return;
          }
        } catch (IOException e) {
          LOG.error("Shuffle error ", e);
          sendError(ctx, e.getMessage(), INTERNAL_SERVER_ERROR);
          return;
        }
      }
      lastMap.addListener(ChannelFutureListener.CLOSE);
    }

    private void verifyRequest(String appid, ChannelHandlerContext ctx,
        HttpRequest request, HttpResponse response, URL requestUri)
        throws IOException {
      SecretKey tokenSecret = secretManager.retrieveTokenSecret(appid);
      if (null == tokenSecret) {
        LOG.info("Request for unknown token " + appid);
        throw new IOException("could not find jobid");
      }
      // string to encrypt
      String enc_str = SecureShuffleUtils.buildMsgFrom(requestUri);
      // hash from the fetcher
      String urlHashStr =
        request.getHeader(SecureShuffleUtils.HTTP_HEADER_URL_HASH);
      if (urlHashStr == null) {
        LOG.info("Missing header hash for " + appid);
        throw new IOException("fetcher cannot be authenticated");
      }
      if (LOG.isDebugEnabled()) {
        int len = urlHashStr.length();
        LOG.debug("verifying request. enc_str=" + enc_str + "; hash=..." +
            urlHashStr.substring(len-len/2, len-1));
      }
      // verify - throws exception
      SecureShuffleUtils.verifyReply(urlHashStr, enc_str, tokenSecret);
      // verification passed - encode the reply
      String reply =
        SecureShuffleUtils.generateHash(urlHashStr.getBytes(), tokenSecret);
      response.setHeader(SecureShuffleUtils.HTTP_HEADER_REPLY_URL_HASH, reply);
      if (LOG.isDebugEnabled()) {
        int len = reply.length();
        LOG.debug("Fetcher request verfied. enc_str=" + enc_str + ";reply=" +
            reply.substring(len-len/2, len-1));
      }
    }

    protected ChannelFuture sendMapOutput(ChannelHandlerContext ctx, Channel ch,
        String user, String jobId, String mapId, int reduce)
        throws IOException {
      // TODO replace w/ rsrc alloc
      // $x/$user/appcache/$appId/output/$mapId
      // TODO: Once Shuffle is out of NM, this can use MR APIs to convert between App and Job
      JobID jobID = JobID.forName(jobId);
      ApplicationId appID = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(ApplicationId.class);
      appID.setClusterTimestamp(Long.parseLong(jobID.getJtIdentifier()));
      appID.setId(jobID.getId());
      final String base =
          ApplicationLocalizer.USERCACHE + "/" + user + "/"
              + ApplicationLocalizer.APPCACHE + "/"
              + ConverterUtils.toString(appID) + "/output" + "/" + mapId;
      LOG.debug("DEBUG0 " + base);
      // Index file
      Path indexFileName = lDirAlloc.getLocalPathToRead(
          base + "/file.out.index", conf);
      // Map-output file
      Path mapOutputFileName = lDirAlloc.getLocalPathToRead(
          base + "/file.out", conf);
      LOG.debug("DEBUG1 " + base + " : " + mapOutputFileName + " : " +
          indexFileName);
      IndexRecord info = 
        indexCache.getIndexInformation(mapId, reduce, indexFileName);
      final ShuffleHeader header =
        new ShuffleHeader(mapId, info.partLength, info.rawLength, reduce);
      final DataOutputBuffer dob = new DataOutputBuffer();
      header.write(dob);
      ch.write(wrappedBuffer(dob.getData(), 0, dob.getLength()));
      File spillfile = new File(mapOutputFileName.toString());
      RandomAccessFile spill;
      try {
        spill = new RandomAccessFile(spillfile, "r");
      } catch (FileNotFoundException e) {
        LOG.info(spillfile + " not found");
        return null;
      }
      final FileRegion partition = new DefaultFileRegion(
          spill.getChannel(), info.startOffset, info.partLength);
      ChannelFuture writeFuture = ch.write(partition);
      writeFuture.addListener(new ChannelFutureListener() {
          // TODO error handling; distinguish IO/connection failures,
          //      attribute to appropriate spill output
          @Override
          public void operationComplete(ChannelFuture future) {
            partition.releaseExternalResources();
          }
        });
      return writeFuture;
    }

    private void sendError(ChannelHandlerContext ctx,
        HttpResponseStatus status) {
      sendError(ctx, "", status);
    }

    private void sendError(ChannelHandlerContext ctx, String message,
        HttpResponseStatus status) {
      HttpResponse response = new DefaultHttpResponse(HTTP_1_1, status);
      response.setHeader(CONTENT_TYPE, "text/plain; charset=UTF-8");
      response.setContent(
        ChannelBuffers.copiedBuffer(message, CharsetUtil.UTF_8));

      // Close the connection as soon as the error message is sent.
      ctx.getChannel().write(response).addListener(ChannelFutureListener.CLOSE);
    }

    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
        throws Exception {
      Channel ch = e.getChannel();
      Throwable cause = e.getCause();
      if (cause instanceof TooLongFrameException) {
        sendError(ctx, BAD_REQUEST);
        return;
      }

      cause.printStackTrace();
      if (ch.isConnected()) {
        LOG.error("Shuffle error " + e);
        sendError(ctx, INTERNAL_SERVER_ERROR);
      }
    }

  }
}
