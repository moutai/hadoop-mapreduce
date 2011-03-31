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

package org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer;

import java.net.URISyntaxException;
import java.util.Random;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.LocalResource;
import org.apache.hadoop.yarn.util.ConverterUtils;

import static org.apache.hadoop.yarn.api.records.LocalResourceType.*;
import static org.apache.hadoop.yarn.api.records.LocalResourceVisibility.*;

import org.junit.Test;
import static org.junit.Assert.*;

public class TestLocalResource {

  static org.apache.hadoop.yarn.api.records.LocalResource getYarnResource(Path p, long size,
      long timestamp, LocalResourceType type, LocalResourceVisibility state)
      throws URISyntaxException {
    org.apache.hadoop.yarn.api.records.LocalResource ret = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(org.apache.hadoop.yarn.api.records.LocalResource.class);
    ret.setResource(ConverterUtils.getYarnUrlFromURI(p.toUri()));
    ret.setSize(size);
    ret.setTimestamp(timestamp);
    ret.setType(type);
    ret.setVisibility(state);
    return ret;
  }

  static void checkEqual(LocalResource a, LocalResource b) {
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertEquals(0, a.compareTo(b));
    assertEquals(0, b.compareTo(a));
  }

  static void checkNotEqual(LocalResource a, LocalResource b) {
    assertFalse(a.equals(b));
    assertFalse(b.equals(a));
    assertFalse(a.hashCode() == b.hashCode());
    assertFalse(0 == a.compareTo(b));
    assertFalse(0 == b.compareTo(a));
  }

  @Test
  public void testResourceEquality() throws URISyntaxException {
    Random r = new Random();
    long seed = r.nextLong();
    r.setSeed(seed);
    System.out.println("SEED: " + seed);

    long basetime = r.nextLong() >>> 2;
    org.apache.hadoop.yarn.api.records.LocalResource yA = getYarnResource(
        new Path("http://yak.org:80/foobar"), -1, basetime, FILE, PUBLIC);
    org.apache.hadoop.yarn.api.records.LocalResource yB = getYarnResource(
        new Path("http://yak.org:80/foobar"), -1, basetime, FILE, PUBLIC);
    final LocalResource a = new LocalResource(yA);
    LocalResource b = new LocalResource(yA);
    checkEqual(a, b);
    b = new LocalResource(yB);
    checkEqual(a, b);

    // ignore visibility
    yB = getYarnResource(
        new Path("http://yak.org:80/foobar"), -1, basetime, FILE, PRIVATE);
    b = new LocalResource(yB);
    checkEqual(a, b);

    // ignore size
    yB = getYarnResource(
        new Path("http://yak.org:80/foobar"), 0, basetime, FILE, PRIVATE);
    b = new LocalResource(yB);
    checkEqual(a, b);

    // note path
    yB = getYarnResource(
        new Path("hdfs://dingo.org:80/foobar"), 0, basetime, ARCHIVE, PUBLIC);
    b = new LocalResource(yB);
    checkNotEqual(a, b);

    // note type
    yB = getYarnResource(
        new Path("http://yak.org:80/foobar"), 0, basetime, ARCHIVE, PUBLIC);
    b = new LocalResource(yB);
    checkNotEqual(a, b);

    // note timestamp
    yB = getYarnResource(
        new Path("http://yak.org:80/foobar"), 0, basetime + 1, FILE, PUBLIC);
    b = new LocalResource(yB);
    checkNotEqual(a, b);
  }

  @Test
  public void testResourceOrder() throws URISyntaxException {
    Random r = new Random();
    long seed = r.nextLong();
    r.setSeed(seed);
    System.out.println("SEED: " + seed);
    long basetime = r.nextLong() >>> 2;
    org.apache.hadoop.yarn.api.records.LocalResource yA = getYarnResource(
        new Path("http://yak.org:80/foobar"), -1, basetime, FILE, PUBLIC);
    final LocalResource a = new LocalResource(yA);

    // Path primary
    org.apache.hadoop.yarn.api.records.LocalResource yB = getYarnResource(
        new Path("http://yak.org:80/foobaz"), -1, basetime, FILE, PUBLIC);
    LocalResource b = new LocalResource(yB);
    assertTrue(0 > a.compareTo(b));

    // timestamp secondary
    yB = getYarnResource(
        new Path("http://yak.org:80/foobar"), -1, basetime + 1, FILE, PUBLIC);
    b = new LocalResource(yB);
    assertTrue(0 > a.compareTo(b));

    // type tertiary
    yB = getYarnResource(
        new Path("http://yak.org:80/foobar"), -1, basetime, ARCHIVE, PUBLIC);
    b = new LocalResource(yB);
    assertTrue(0 != a.compareTo(b)); // don't care about order, just ne
  }

}
