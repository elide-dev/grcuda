package com.nvidia.grcuda.gpu.computation;

import com.nvidia.grcuda.array.AbstractArray;
import com.nvidia.grcuda.gpu.executioncontext.AbstractGrCUDAExecutionContext;
import com.nvidia.grcuda.gpu.executioncontext.GrCUDAExecutionContext;
import com.nvidia.grcuda.gpu.stream.CUDAStream;
import com.nvidia.grcuda.gpu.stream.DefaultStream;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.interop.UnsupportedTypeException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Basic class that represents GrCUDA computations,
 * and is used to model data dependencies between computations;
 */
public abstract class GrCUDAComputationalElement {

    /**
     * This list contains the original set of input arguments that are used to compute dependencies;
     */
    protected final List<ComputationArgumentWithValue> argumentList;
    /**
     * Reference to the execution context where this computation is executed;
     */
    protected final AbstractGrCUDAExecutionContext grCUDAExecutionContext;
    /**
     * Reference to the stream where this computation will be executed,
     * if possible (i.e. if the computation can be executed on a custom stream).
     * Subclasses can keep an internal reference to the stream, e.g. if it can be manually modified by the user,
     * but it is required to keep that value consistent to this one if it is modified;
     */
    private CUDAStream stream = new DefaultStream();
    /**
     * Keep track of whether this computation has already been executed, and represents a "dead" vertex in the DAG.
     * Computations that are already executed will not be considered when computing dependencies;
     */
    private boolean computationFinished = false;
    /**
     * Keep track of whether this computation has already been started, to avoid performing the same computation multiple times;
     */
    private boolean computationStarted = false;
    /**
     * Specify if this computational element represents an array access (read or write) on an {@link com.nvidia.grcuda.array.AbstractArray}
     * performed synchronously by the CPU. By default it returns false;
     */
    protected boolean isComputationArrayAccess = false;

    private final DependencyComputation dependencyComputation;

    /**
     * Constructor that takes an argument set initializer to build the set of arguments used in the dependency computation
     * @param grCUDAExecutionContext execution context in which this computational element will be scheduled
     * @param initializer the initializer used to build the internal set of arguments considered in the dependency computation
     */
    @CompilerDirectives.TruffleBoundary
    public GrCUDAComputationalElement(AbstractGrCUDAExecutionContext grCUDAExecutionContext, InitializeArgumentList initializer) {
        this.argumentList = initializer.initialize();
        // Initialize by making a copy of the original set;
        this.grCUDAExecutionContext = grCUDAExecutionContext;
        this.dependencyComputation = new DefaultDependencyComputation(this.argumentList);
    }

    /**
     * Simplified constructor that takes a list of arguments, and consider all of them in the dependency computation
     * @param grCUDAExecutionContext execution context in which this computational element will be scheduled
     * @param args the list of arguments provided to the computation. Arguments are expected to be {@link org.graalvm.polyglot.Value}
     */
    public GrCUDAComputationalElement(AbstractGrCUDAExecutionContext grCUDAExecutionContext, List<ComputationArgumentWithValue> args) {
        this(grCUDAExecutionContext, new DefaultExecutionInitializer(args));
    }

    public List<ComputationArgumentWithValue> getArgumentList() {
        return argumentList;
    }

    /**
     * Return if this computation could lead to dependencies with future computations.
     * If not, this usually means that all of its arguments have already been superseded by other computations,
     * or that the computation didn't have any arguments to begin with;
     * @return if the computation could lead to future dependencies
     */
    public boolean hasPossibleDependencies() {
        return !this.dependencyComputation.getActiveArgumentSet().isEmpty();
    }

    /**
     * Schedule this computation for future execution by the {@link GrCUDAExecutionContext}.
     * The scheduling request is separate from the {@link GrCUDAComputationalElement} instantiation
     * as we need to ensure that the the computational element subclass has been completely instantiated;
     */
    public Object schedule() throws UnsupportedTypeException {
        return this.grCUDAExecutionContext.registerExecution(this);
    }

    /**
     * Generic interface to perform the execution of this {@link GrCUDAComputationalElement}.
     * The actual execution implementation must be added by concrete computational elements.
     * The execution request will be done by the {@link GrCUDAExecutionContext}, after this computation has been scheduled
     * using {@link GrCUDAComputationalElement#schedule()}
     */
    public abstract Object execute() throws UnsupportedTypeException;

    public CUDAStream getStream() {
        return stream;
    }

    public void setStream(CUDAStream stream) {
        this.stream = stream;
    }

    public boolean isComputationFinished() {
        return computationFinished;
    }

    public boolean isComputationStarted() {
        return computationStarted;
    }

    public void setComputationFinished() {
        this.computationFinished = true;
    }

    public void setComputationStarted() {
        this.computationStarted = true;
    }

    /**
     * Find whether this computation should be done on a user-specified {@link com.nvidia.grcuda.gpu.stream.CUDAStream};
     * If not, the stream will be provided internally using the specified execution policy. By default return false;
     * @return if the computation is done on a custom CUDA stream;
     */
    public boolean useManuallySpecifiedStream() { return false; }

    /**
     * Some computational elements, like kernels, can be executed on different {@link CUDAStream} to provide
     * parallel asynchronous execution. Other computations, such as array reads, do not require streams, or cannot be
     * executed on streams different from the {@link DefaultStream};
     * @return if this computation can be executed on a customized stream
     */
    public boolean canUseStream() { return false; }

    /**
     * Provide a way to associate input arrays allocated using managed memory to the stream
     * on which this kernel is executed. This is required by pre-Pascal GPUs to allow the CPU to access
     * managed memory belonging to arrays not used by kernels running on the GPU.
     * By default, the implementation is empty, as {@link GrCUDAComputationalElement#canUseStream} is false;
     */
    public final void associateArraysToStream() {
        grCUDAExecutionContext.getArrayStreamArchitecturePolicy().execute(this::associateArraysToStreamImpl);
    }

    /**
     * Actual implementation of {@link GrCUDAComputationalElement#associateArraysToStream()},
     * to be modified by concrete computational elements;
     */
    protected void associateArraysToStreamImpl() {}

    /**
     * Set for all the {@link com.nvidia.grcuda.array.AbstractArray} in the computation if this computation is an array access;
     */
    public void updateIsComputationArrayAccess() {
        for (ComputationArgumentWithValue o : this.argumentList) {
            if (o.getArgumentValue() instanceof AbstractArray) {
                ((AbstractArray) o.getArgumentValue()).setLastComputationArrayAccess(isComputationArrayAccess);
            }
        }
    }

    /**
     * Computes if the "other" GrCUDAComputationalElement has dependencies w.r.t. this kernel,
     * such as requiring as input a value computed by this kernel;
     * @param other kernel for which we want to check dependencies, w.r.t. this kernel
     * @return the list of arguments that the two kernels have in common
     */
    public Collection<ComputationArgumentWithValue> computeDependencies(GrCUDAComputationalElement other) {
        return this.dependencyComputation.computeDependencies(other);
    }

    /**
     * The default initializer will simply store all the arguments,
     * and consider each of them in the dependency computations;
     */
    private static class DefaultExecutionInitializer implements InitializeArgumentList {
        private final List<ComputationArgumentWithValue> args;

        DefaultExecutionInitializer(List<ComputationArgumentWithValue> args) {
            this.args = args;
        }

        @Override
        public List<ComputationArgumentWithValue> initialize() {
            return args;
        }
    }

    /**
     * By default, consider all dependencies in the active argument set,
     * initially specified by the {@link InitializeArgumentList} interface.
     * Also update the active argument set, by adding all arguments that were not included in a dependency relation;
     */
    private static class DefaultDependencyComputation extends DependencyComputation {

        @CompilerDirectives.TruffleBoundary
        DefaultDependencyComputation(List<ComputationArgumentWithValue> argumentList) {
            activeArgumentSet = new HashSet<>(argumentList);
        }

        @CompilerDirectives.TruffleBoundary
        @Override
        public List<ComputationArgumentWithValue> computeDependencies(GrCUDAComputationalElement other) {
            Set<ComputationArgumentWithValue> dependencies = new HashSet<>();
            Set<ComputationArgumentWithValue> newArgumentSet = new HashSet<>();
            for (ComputationArgumentWithValue arg : activeArgumentSet) {
                // The other computation requires the current argument, so we have found a new dependency;
                if (other.dependencyComputation.getActiveArgumentSet().contains(arg)) {
                    dependencies.add(arg);
                } else {
                    // Otherwise, the current argument is still "active", and could enforce a dependency on a future computation;
                    newArgumentSet.add(arg);
                }
            }
            // Arguments that are not leading to a new dependency could still create new dependencies later on!
            activeArgumentSet = newArgumentSet;
            // Return the list of arguments that created dependencies with the new computation;
            return new ArrayList<>(dependencies);
        }
    }

    /**
     * If two computations have the same argument, but it is read-only in both cases (i.e. const),
     * there is no reason to create a dependency between the two ;
     */
    private class WithConstDependencyComputation extends DependencyComputation {

        WithConstDependencyComputation(List<ComputationArgumentWithValue> argumentList) {
            activeArgumentSet = new ArrayList<>(argumentList);
        }

        @Override
        public List<ComputationArgumentWithValue> computeDependencies(GrCUDAComputationalElement other) {
            List<ComputationArgumentWithValue> dependencies = new ArrayList<>();
            // FIXME: the active argument set could be something else, e.g. a collection?
            //  it might make sense to have different types depending on how dependencies are computed;
            List<ComputationArgumentWithValue> newArgumentSet = new ArrayList<>();
            for (ComputationArgumentWithValue arg : activeArgumentSet) {
                boolean dependencyFound = false;
                for (ComputationArgumentWithValue otherArg : other.dependencyComputation.getActiveArgumentSet()) {
                    // If both arguments are const, we skip the dependency;
                    if (arg.equals(otherArg) && !(arg.isConst() && otherArg.isConst())) {
                        dependencies.add(arg);
                        dependencyFound = true;
                        break;
                    }
                }
                if (!dependencyFound) {
                    // Otherwise, the current argument is still "active", and could enforce a dependency on a future computation;
                    newArgumentSet.add(arg);
                }
            }
            // Arguments that are not leading to a new dependency could still create new dependencies later on!
            activeArgumentSet = newArgumentSet;
            // Return the list of arguments that created dependencies with the new computation;
            return dependencies;
        }
    }
}
