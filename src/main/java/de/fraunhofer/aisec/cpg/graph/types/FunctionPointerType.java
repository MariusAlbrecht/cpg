package de.fraunhofer.aisec.cpg.graph.types;

import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.neo4j.ogm.annotation.Relationship;

/**
 * FunctionPointerType represents FunctionPointers in CPP containing a list of parameters and a
 * return type.
 */
public class FunctionPointerType extends Type {
  @Relationship(value = "parameters", direction = "OUTGOING")
  private List<PropertyEdge> parameters;

  private Type returnType;

  public void setParameters(List<Type> parameters) {
    this.parameters = PropertyEdge.transformIntoPropertyEdgeList(parameters, this, true);
  }

  public void setReturnType(Type returnType) {
    this.returnType = returnType;
  }

  private FunctionPointerType() {}

  public FunctionPointerType(
      Type.Qualifier qualifier, Type.Storage storage, List<Type> parameters, Type returnType) {
    super("", storage, qualifier);
    this.parameters = PropertyEdge.transformIntoPropertyEdgeList(parameters, this, true);
    this.returnType = returnType;
  }

  public FunctionPointerType(Type type, List<Type> parameters, Type returnType) {
    super(type);
    this.parameters = PropertyEdge.transformIntoPropertyEdgeList(parameters, this, true);
    this.returnType = returnType;
  }

  public List<PropertyEdge> getParametersPropertyEdge() {
    return this.parameters;
  }

  public List<Type> getParameters() {
    List<Type> target = new ArrayList<>();
    for (PropertyEdge propertyEdge : this.parameters) {
      target.add((Type) propertyEdge.getEnd());
    }
    return Collections.unmodifiableList(target);
  }

  public Type getReturnType() {
    return returnType;
  }

  @Override
  public PointerType reference(PointerType.PointerOrigin pointerOrigin) {
    return new PointerType(this, pointerOrigin);
  }

  @Override
  public Type dereference() {
    return this;
  }

  @Override
  public Type duplicate() {
    return new FunctionPointerType(
        this, (List<Type>) PropertyEdge.getTarget(this.parameters, true), this.returnType);
  }

  @Override
  public boolean isSimilar(Type t) {
    if (t instanceof FunctionPointerType) {
      if (returnType == null || ((FunctionPointerType) t).returnType == null) {
        return this.parameters.equals(((FunctionPointerType) t).parameters)
            && (this.returnType == ((FunctionPointerType) t).returnType
                || returnType == ((FunctionPointerType) t).getReturnType());
      }
      return this.parameters.equals(((FunctionPointerType) t).parameters)
          && (this.returnType == ((FunctionPointerType) t).returnType
              || this.returnType.equals(((FunctionPointerType) t).returnType));
    }
    return false;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof FunctionPointerType)) return false;
    if (!super.equals(o)) return false;
    FunctionPointerType that = (FunctionPointerType) o;
    return Objects.equals(parameters, that.parameters)
        && Objects.equals(this.getParameters(), that.getParameters())
        && Objects.equals(returnType, that.returnType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), parameters, returnType);
  }

  @Override
  public String toString() {
    return "FunctionPointerType{"
        + "parameters="
        + parameters
        + ", returnType="
        + returnType
        + ", typeName='"
        + name
        + '\''
        + ", storage="
        + this.getStorage()
        + ", qualifier="
        + this.getQualifier()
        + ", origin="
        + this.getTypeOrigin()
        + '}';
  }
}
