/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.sys.store;

import static org.apache.drill.exec.ExecConstants.DRILL_SYS_FILE_SUFFIX;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import org.apache.commons.io.IOUtils;
import org.apache.drill.common.collections.ImmutableEntry;
import org.apache.drill.common.config.DrillConfig;
import org.apache.drill.exec.store.dfs.DrillFileSystem;
import org.apache.drill.exec.store.sys.BasePersistentStore;
import org.apache.drill.exec.store.sys.PersistentStoreConfig;
import org.apache.drill.exec.store.sys.PersistentStoreMode;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class LocalPersistentStore<V> extends BasePersistentStore<V> {
//  private static final Logger logger = LoggerFactory.getLogger(LocalPersistentStore.class);

  private final Path basePath;
  private final PersistentStoreConfig<V> config;
  private final DrillFileSystem fs;

  public LocalPersistentStore(DrillFileSystem fs, Path base, PersistentStoreConfig<V> config) {
    super();
    this.basePath = new Path(base, config.getName());
    this.config = config;
    this.fs = fs;

    try {
      mkdirs(basePath);
    } catch (IOException e) {
      throw new RuntimeException("Failure setting pstore configuration path.");
    }
  }

  @Override
  public PersistentStoreMode getMode() {
    return PersistentStoreMode.PERSISTENT;
  }

  private void mkdirs(Path path) throws IOException{
    fs.mkdirs(path);
  }

  public static Path getLogDir(){
    String drillLogDir = System.getenv("DRILL_LOG_DIR");
    if (drillLogDir == null) {
      drillLogDir = System.getProperty("drill.log.dir");
    }
    if (drillLogDir == null) {
      drillLogDir = "/var/log/drill";
    }
    return new Path(new File(drillLogDir).getAbsoluteFile().toURI());
  }

  public static DrillFileSystem getFileSystem(DrillConfig config, Path root) throws IOException{
    Path blobRoot = root == null ? getLogDir() : root;
    Configuration fsConf = new Configuration();
    if(blobRoot.toUri().getScheme() != null){
      fsConf.set(FileSystem.FS_DEFAULT_NAME_KEY, blobRoot.toUri().toString());
    }


    DrillFileSystem fs = new DrillFileSystem(fsConf);
    fs.mkdirs(blobRoot);
    return fs;
  }

  @Override
  public Iterator<Map.Entry<String, V>> getRange(int skip, int take) {
    try{
      List<FileStatus> f = fs.list(false, basePath);
      if (f == null || f.isEmpty()) {
        return Collections.emptyIterator();
      }
      List<String> files = Lists.newArrayList();

      for (FileStatus stat : f) {
        String s = stat.getPath().getName();
        if (s.endsWith(DRILL_SYS_FILE_SUFFIX)) {
          files.add(s.substring(0, s.length() - DRILL_SYS_FILE_SUFFIX.length()));
        }
      }

      Collections.sort(files);
      return Iterables.transform(Iterables.limit(Iterables.skip(files, skip), take), new Function<String, Entry<String, V>>() {
        @Nullable
        @Override
        public Entry<String, V> apply(String key) {
          return new ImmutableEntry<>(key, get(key));
        }
      }).iterator();
    }catch(IOException e){
      throw new RuntimeException(e);
    }
  }

  private Path makePath(String name) {
    Preconditions.checkArgument(
        !name.contains("/") &&
        !name.contains(":") &&
        !name.contains(".."));

    final Path path = new Path(basePath, name + DRILL_SYS_FILE_SUFFIX);
    // do this to check file name.
    return path;
  }

  @Override
  public V get(String key) {
    try{
      Path path = makePath(key);
      if(!fs.exists(path)){
        return null;
      }
    }catch(IOException e){
      throw new RuntimeException(e);
    }

    final Path path = makePath(key);
    try (InputStream is = fs.open(path)) {
      return config.getSerializer().deserialize(IOUtils.toByteArray(is));
    } catch (IOException e) {
      throw new RuntimeException("Unable to deserialize \"" + path + "\"", e);
    }
  }

  @Override
  public void put(String key, V value) {
    try (OutputStream os = fs.create(makePath(key))) {
      IOUtils.write(config.getSerializer().serialize(value), os);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean putIfAbsent(String key, V value) {
    try {
      Path p = makePath(key);
      if (fs.exists(p)) {
        return false;
      } else {
        put(key, value);
        return true;
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      fs.delete(makePath(key), false);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() {
  }

}
