package org.apache.drill.exec.store.revised;

import org.apache.drill.exec.store.revised.Sketch.Deserializer;
import org.apache.drill.exec.store.revised.Sketch.ScanReceiver;
import org.apache.drill.exec.store.revised.Sketch.ScanService;

public abstract class AbstractDeserializer implements Deserializer {

  private ScanService scanService;

  @Override
  public void bind(ScanService service) {
    scanService = service;
  }

  public ScanService scanService() { return scanService; }
  public ScanReceiver receiver() { return scanService.receiver(); }

  @Override
  public void open() throws Exception {
  }

  @Override
  public void close() throws Exception {
  }

}
