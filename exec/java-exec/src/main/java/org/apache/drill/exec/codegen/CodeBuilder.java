package org.apache.drill.exec.codegen;

import java.io.IOException;

import org.apache.drill.exec.exception.ClassTransformationException;
import org.apache.drill.exec.expr.CodeGenerator;
import org.apache.drill.exec.ops.FragmentContext;

public abstract class CodeBuilder<T> {

  FragmentContext context;
  CodeGenerator<T> cg;
  private CachedClassLoader classLoader;
  boolean straightJava;
  boolean useCache = true;

  public CodeBuilder( FragmentContext context ) {
    this.context = context;
    classLoader = new CachedClassLoader( );
  }

  public void setStraightJava( boolean flag ) {
    straightJava = flag;
  }

  public void useCache( boolean flag ) {
    useCache = flag;
  }

  @SuppressWarnings("unchecked")
  public Class<T> load( ) throws ClassTransformationException {
    if ( straightJava ) {
      // Do something
      String className = null; // TODO
      try {
        return (Class<T>) classLoader.findClass( className );
      } catch (ClassNotFoundException e) {
        throw new ClassTransformationException(e);
      }
    } else {
      return (Class<T>) context.getImplementationClass( getCg( ) );
    }
  }

  public T newInstance( ) throws ClassTransformationException, IOException {
    if ( straightJava ) {
      try {
        return load( ).newInstance( );
      } catch (InstantiationException | IllegalAccessException e) {
        throw new ClassTransformationException(e);
      }
    } else {
      return context.getImplementationClass( getCg( ) );
    }
  }

  private CodeGenerator<T> getCg( ) {
    if ( cg == null )
      cg = build( );
    return cg;
  }

  protected abstract CodeGenerator<T> build( );
}
