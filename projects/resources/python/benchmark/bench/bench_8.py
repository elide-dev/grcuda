# coding=utf-8
import polyglot
import time
import numpy as np
from random import random, randint, seed, sample

from benchmark import Benchmark, time_phase
from benchmark_result import BenchmarkResult

##############################
##############################

NUM_THREADS_PER_BLOCK = 32

GAUSSIAN_BLUR = """
extern "C" __global__ void gaussian_blur(const float *image, float *result, int rows, int cols, const float* kernel, int diameter) {
    int i = blockDim.x * blockIdx.x + threadIdx.x;
    int j = blockDim.y * blockIdx.y + threadIdx.y;
    if (i < rows && j < cols) {
        float sum = 0;
        int radius = diameter / 2;
        for (int x = -radius; x <= radius; ++x) {
            for (int y = -radius; y <= radius; ++y) {
                int nx = x + i;
                int ny = y + j;
                if (nx >= 0 && ny >= 0 && nx < rows && ny < cols) {
                    sum += kernel[(x + radius) * diameter + (y + radius)] * image[nx * cols + ny];
                }
            }
        }
        result[i * cols + j] = sum;
    }
}
"""


SOBEL = """
extern "C" __global__ void sobel(const float *image, float *result, int rows, int cols) {
    float SOBEL_X[3][3] = {{-1, -2, -1}, {0, 0, 0}, {1, 2, 1}};
    float SOBEL_Y[3][3] = {{-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1}};
    
    int i = blockDim.x * blockIdx.x + threadIdx.x;
    int j = blockDim.y * blockIdx.y + threadIdx.y;
    if (i < rows && j < cols) {
        float sum_gradient_x = 0.0, sum_gradient_y = 0.0;
        int radius = 1;
        for (int x = -radius; x <= radius; ++x) {
            for (int y = -radius; y <= radius; ++y) {
                int nx = x + i;
                int ny = y + j;
                if (nx >= 0 && ny >= 0 && nx < rows && ny < cols) {
                    float neighbour = image[nx * cols + ny];
                    sum_gradient_x += SOBEL_X[x+ radius][y + radius] * neighbour;
                    sum_gradient_y += SOBEL_Y[x+ radius][y + radius] * neighbour;
                }
            }
        }
        result[i * cols + j] = sqrt(sum_gradient_x * sum_gradient_x + sum_gradient_y * sum_gradient_y);
    }
}
"""

EXTEND_MASK = """
__device__ float atomicMaxf(float* address, float val)
{
    int *address_as_int =(int*)address;
    int old = *address_as_int, assumed;
    while (val > __int_as_float(old)) {
        assumed = old;
        old = atomicCAS(address_as_int, assumed,
                        __float_as_int(val));
        }
    return __int_as_float(old);
}

__device__ float atomicMinf(float* address, float val)
{
    int *address_as_int =(int*)address;
    int old = *address_as_int, assumed;
    while (val < __int_as_float(old)) {
        assumed = old;
        old = atomicCAS(address_as_int, assumed,
                        __float_as_int(val));
        }
    return __int_as_float(old);
}

extern "C" __global__ void maximum(const float *x, float *res, int n) {
    extern __shared__ float shared[%d];

    int tid = threadIdx.x;
    int gid = (blockDim.x * blockIdx.x) + tid;
    shared[tid] = -1000; // Some small value; 

    while (gid < n) {
        shared[tid] = max(shared[tid], x[gid]);
        gid += gridDim.x*blockDim.x;
        }
    __syncthreads();
    gid = (blockDim.x * blockIdx.x) + tid;  // 1
    for (unsigned int s=blockDim.x/2; s>0; s>>=1) 
    {
        if (tid < s && gid < n)
            shared[tid] = max(shared[tid], shared[tid + s]);
        __syncthreads();
    }

    if (tid == 0)
      atomicMaxf(res, shared[0]);
}
    
extern "C" __global__ void minimum(const float *x, float *res, int n) {
    extern __shared__ float shared[%d];

    int tid = threadIdx.x;
    int gid = (blockDim.x * blockIdx.x) + tid;
    shared[tid] = 1000; // Some big value; 

    while (gid < n) {
        shared[tid] = min(shared[tid], x[gid]);
        gid += gridDim.x*blockDim.x;
        }
    __syncthreads();
    gid = (blockDim.x * blockIdx.x) + tid;  // 1
    for (unsigned int s=blockDim.x/2; s>0; s>>=1) 
    {
        if (tid < s && gid < n)
            shared[tid] = min(shared[tid], shared[tid + s]);
        __syncthreads();
    }

    if (tid == 0)
      atomicMinf(res, shared[0]);
}

extern "C" __global__ void extend(float *x, const float *minimum, const float *maximum, int n) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i < n) {
        float res_tmp = 5 * (x[i] - *minimum) / (*maximum - *minimum);
        x[i] = res_tmp > 1 ? 1 : res_tmp;
    }
}
""" % (NUM_THREADS_PER_BLOCK, NUM_THREADS_PER_BLOCK)

UNSHARPEN = """
extern "C" __global__ void unsharpen(const float *x, const float *y, float *res, float amount, int n) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i < n) {
        float res_tmp = x[i] * (1 + amount) - y[i] * amount;
        res_tmp = res_tmp > 1 ? 1 : res_tmp;
        res[i] = res_tmp < 0 ? 0 : res_tmp;
    }
}
"""

COMBINE = """
extern "C" __global__ void combine(const float *x, const float *y, const float *mask, float *res, int n) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i < n) {
        res[i] = x[i] * mask[i] + y[i] * (1 - mask[i]);
    }
}
"""


##############################
##############################


class Benchmark8(Benchmark):
    """
    Compute an image processing pipeline in which we sharpen an image and combine it
    with copies that have been blurred at low and medium frequencies. The result is an image sharper on the edges,
    and softer everywhere else: this filter is common, for example, in portrait retouching, where a photographer desires
    to enhance the clarity of facial features while smoothing the subject' skin and the background;

    BLUR(image,blur1) ─> SOBEL(blur1,mask1) ───────────────────────────────────────────────────────────────────────────────┐
    BLUR(image,blur2) ─> SOBEL(blur2,mask2) ┬─> MAX(mask2) ──┬─> EXTEND(mask2) ──┐                                         │
                                            └─> MIN(mask2) ──┘                   │                                         │
    SHARPEN(image,blur3) ─> UNSHARPEN(image,blur3,sharpened) ────────────────────┴─> COMBINE(sharpened,blur2,mask2,image2) ┴─> COMBINE(image2,blur1,mask1,image3)
    """

    def __init__(self, benchmark: BenchmarkResult):
        super().__init__("b8", benchmark)
        self.size = 0

        self.image = None
        self.image2 = None
        self.image3 = None

        self.blurred_small = None
        self.mask_small = None
        self.kernel_small = None
        self.kernel_small_diameter = 3
        self.kernel_small_variance = 1

        self.blurred_large = None
        self.mask_large = None
        self.kernel_large = None
        self.kernel_large_diameter = 5
        self.kernel_large_variance = 10
        self.maximum = None
        self.minimum = None

        self.blurred_unsharpen = None
        self.image_unsharpen = None
        self.kernel_unsharpen = None
        self.kernel_unsharpen_diameter = 3
        self.kernel_unsharpen_variance = 5
        self.unsharpen_amount = 0.5

        self.image_cpu = None
        self.kernel_small_cpu = None
        self.kernel_large_cpu = None
        self.kernel_unsharpen_cpu = None

        self.cpu_result = None
        self.gpu_result = None

        self.num_blocks_size = 0

        self.gaussian_blur_kernel = None
        self.sobel_kernel = None
        self.extend_kernel = None
        self.unsharpen_kernel = None
        self.combine_mask_kernel = None
        self.maximum_kernel = None
        self.minimum_kernel = None

    @time_phase("allocation")
    def alloc(self, size: int):
        self.size = size
        self.num_blocks_size = (size + NUM_THREADS_PER_BLOCK - 1) // NUM_THREADS_PER_BLOCK

        self.gpu_result = np.zeros(self.size)

        # Allocate vectors;
        self.image = polyglot.eval(language="grcuda", string=f"float[{size}][{size}]")
        self.image2 = polyglot.eval(language="grcuda", string=f"float[{size}][{size}]")
        self.image3 = polyglot.eval(language="grcuda", string=f"float[{size}][{size}]")

        self.kernel_small = polyglot.eval(language="grcuda", string=f"float[{self.kernel_small_diameter}][{self.kernel_small_diameter}]")
        self.kernel_large = polyglot.eval(language="grcuda", string=f"float[{self.kernel_large_diameter}][{self.kernel_large_diameter}]")
        self.kernel_unsharpen = polyglot.eval(language="grcuda", string=f"float[{self.kernel_unsharpen_diameter}][{self.kernel_unsharpen_diameter}]")
        self.maximum = polyglot.eval(language="grcuda", string=f"float[1]")
        self.minimum = polyglot.eval(language="grcuda", string=f"float[1]")

        self.mask_small = polyglot.eval(language="grcuda", string=f"float[{size}][{size}]")
        self.mask_large = polyglot.eval(language="grcuda", string=f"float[{size}][{size}]")
        self.image_unsharpen = polyglot.eval(language="grcuda", string=f"float[{size}][{size}]")

        self.blurred_small = polyglot.eval(language="grcuda", string=f"float[{size}][{size}]")
        self.blurred_large = polyglot.eval(language="grcuda", string=f"float[{size}][{size}]")
        self.blurred_unsharpen = polyglot.eval(language="grcuda", string=f"float[{size}][{size}]")

        # Build the kernels;
        build_kernel = polyglot.eval(language="grcuda", string="buildkernel")
        self.gaussian_blur_kernel = build_kernel(GAUSSIAN_BLUR, "gaussian_blur", "const pointer, pointer, sint32, sint32, const pointer, sint32")
        self.sobel_kernel = build_kernel(SOBEL, "sobel", "const pointer, pointer, sint32, sint32")
        self.extend_kernel = build_kernel(EXTEND_MASK, "extend", "pointer, const pointer, const pointer, sint32")
        self.maximum_kernel = build_kernel(EXTEND_MASK, "maximum", "const pointer, pointer, sint32")
        self.minimum_kernel = build_kernel(EXTEND_MASK, "minimum", "const pointer, pointer, sint32")
        self.unsharpen_kernel = build_kernel(UNSHARPEN, "unsharpen", "const pointer, const pointer, pointer, float, sint32")
        self.combine_mask_kernel = build_kernel(COMBINE, "combine", "const pointer, const pointer, const pointer, pointer, sint32")

    @time_phase("initialization")
    def init(self):

        def gaussian_kernel(diameter, sigma):
            kernel = np.zeros((diameter, diameter))
            mean = diameter / 2
            sum_tmp = 0
            for x in range(diameter):
                for y in range(diameter):
                    kernel[x, y] = np.exp(-0.5 * ((x - mean) ** 2 + (y - mean) ** 2) / sigma ** 2)
                    sum_tmp += kernel[x, y]
            for x in range(diameter):
                for y in range(diameter):
                    kernel[x, y] /= sum_tmp
            return kernel

        self.random_seed = randint(0, 10000000)
        seed(self.random_seed)

        # Create a random image;
        self.image_cpu = np.zeros((self.size, self.size))  # Create here the image used for validation;
        for i in range(self.size):
            for j in range(self.size):
                self.image_cpu[i, j] = random()
                self.image[i][j] = float(self.image_cpu[i, j])

        self.kernel_small_cpu = gaussian_kernel(self.kernel_small_diameter, self.kernel_small_variance)
        self.kernel_large_cpu = gaussian_kernel(self.kernel_large_diameter, self.kernel_large_variance)
        self.kernel_unsharpen_cpu = gaussian_kernel(self.kernel_unsharpen_diameter, self.kernel_unsharpen_variance)
        for i in range(self.kernel_small_diameter):
            for j in range(self.kernel_small_diameter):
                self.kernel_small[i][j] = float(self.kernel_small_cpu[i, j])
        for i in range(self.kernel_large_diameter):
            for j in range(self.kernel_large_diameter):
                self.kernel_large[i][j] = float(self.kernel_large_cpu[i, j])
        for i in range(self.kernel_unsharpen_diameter):
            for j in range(self.kernel_unsharpen_diameter):
                self.kernel_unsharpen[i][j] = float(self.kernel_unsharpen_cpu[i, j])

    @time_phase("reset_result")
    def reset_result(self) -> None:
        self.gpu_result = np.zeros((self.size, self.size))
        self.maximum[0] = 0.0
        self.minimum[0] = 0.0

    def execute(self) -> object:

        # Blur - Small;
        start = time.time()
        self.gaussian_blur_kernel((self.num_blocks_size, self.num_blocks_size), (NUM_THREADS_PER_BLOCK, NUM_THREADS_PER_BLOCK))\
            (self.image, self.blurred_small, self.size, self.size, self.kernel_small, self.kernel_small_diameter)
        end = time.time()
        self.benchmark.add_phase({"name": "blur_small", "time_sec": end - start})

        # Blur - Large;
        start = time.time()
        self.gaussian_blur_kernel((self.num_blocks_size, self.num_blocks_size), (NUM_THREADS_PER_BLOCK, NUM_THREADS_PER_BLOCK))\
            (self.image, self.blurred_large, self.size, self.size, self.kernel_large, self.kernel_large_diameter)
        end = time.time()
        self.benchmark.add_phase({"name": "blur_large", "time_sec": end - start})

        # Blur - Unsharpen;
        start = time.time()
        self.gaussian_blur_kernel((self.num_blocks_size, self.num_blocks_size), (NUM_THREADS_PER_BLOCK, NUM_THREADS_PER_BLOCK))\
            (self.image, self.blurred_unsharpen, self.size, self.size, self.kernel_unsharpen, self.kernel_unsharpen_diameter)
        end = time.time()
        self.benchmark.add_phase({"name": "blur_unsharpen", "time_sec": end - start})

        # Sobel filter (edge detection);
        start = time.time()
        self.sobel_kernel((self.num_blocks_size, self.num_blocks_size), (NUM_THREADS_PER_BLOCK, NUM_THREADS_PER_BLOCK))\
            (self.blurred_small, self.mask_small, self.size, self.size)
        end = time.time()
        self.benchmark.add_phase({"name": "sobel_small", "time_sec": end - start})

        start = time.time()
        self.sobel_kernel((self.num_blocks_size, self.num_blocks_size), (NUM_THREADS_PER_BLOCK, NUM_THREADS_PER_BLOCK))\
            (self.blurred_large, self.mask_large, self.size, self.size)
        end = time.time()
        self.benchmark.add_phase({"name": "sobel_large", "time_sec": end - start})

        # Extend large edge detection mask;
        start = time.time()
        num_blocks_tmp = (self.size * self.size + NUM_THREADS_PER_BLOCK - 1) // NUM_THREADS_PER_BLOCK
        self.maximum_kernel(num_blocks_tmp, NUM_THREADS_PER_BLOCK)(self.mask_large, self.maximum, self.size**2)
        self.minimum_kernel(num_blocks_tmp, NUM_THREADS_PER_BLOCK)(self.mask_large, self.minimum, self.size**2)
        self.extend_kernel(num_blocks_tmp, NUM_THREADS_PER_BLOCK)(self.mask_large, self.minimum, self.maximum, self.size**2)
        end = time.time()
        self.benchmark.add_phase({"name": "extend", "time_sec": end - start})

        # Unsharpen;
        start = time.time()
        self.unsharpen_kernel(num_blocks_tmp, NUM_THREADS_PER_BLOCK)\
            (self.image, self.blurred_unsharpen, self.image_unsharpen, self.unsharpen_amount, self.size * self.size)
        end = time.time()
        self.benchmark.add_phase({"name": "unsharpen", "time_sec": end - start})

        # Combine results;
        start = time.time()
        self.combine_mask_kernel(num_blocks_tmp, NUM_THREADS_PER_BLOCK)\
            (self.image_unsharpen, self.blurred_large, self.mask_large, self.image2, self.size * self.size)
        self.combine_mask_kernel(num_blocks_tmp, NUM_THREADS_PER_BLOCK)\
            (self.image2, self.blurred_small, self.mask_small, self.image3, self.size * self.size)
        end = time.time()
        self.benchmark.add_phase({"name": "combine", "time_sec": end - start})

        # Add a final sync step to measure the real computation time;
        start = time.time()
        tmp = self.image3[0][0]
        end = time.time()
        self.benchmark.add_phase({"name": "sync", "time_sec": end - start})

        # Compute GPU result;
        # for i in range(self.size):
        #     for j in range(self.size):
        #         self.gpu_result[i, j] = self.image3[i][j]

        self.benchmark.add_to_benchmark("gpu_result", 0)
        if self.benchmark.debug:
            BenchmarkResult.log_message(
                f"\tgpu result: [" + ", ".join([f"{x:.4f}" for x in self.gpu_result[0, :10]]) + "...]")

        return self.gpu_result

    def cpu_validation(self, gpu_result: object, reinit: bool) -> None:

        sobel_filter_diameter = 3
        sobel_filter_x = np.array([[-1, -2, -1], [0, 0, 0], [1, 2, 1]])
        sobel_filter_y = np.array([[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]])

        def sobel_filter(image):
            out = np.zeros(image.shape)
            rows, cols = image.shape
            radius = sobel_filter_diameter // 2

            for i in range(rows):
                for j in range(cols):
                    sum_gradient_x = 0
                    sum_gradient_y = 0
                    for x in range(-radius, radius + 1):
                        for y in range(-radius, radius + 1):
                            nx = x + i
                            ny = y + j
                            if (nx >= 0 and ny >= 0 and nx < rows and ny < cols):
                                gray_value_neigh = image[nx, ny]
                                gradient_x = sobel_filter_x[x + radius][y + radius]
                                gradient_y = sobel_filter_y[x + radius][y + radius]
                                sum_gradient_x += gray_value_neigh * gradient_x
                                sum_gradient_y += gray_value_neigh * gradient_y
                    out[i, j] = np.sqrt(sum_gradient_x ** 2 + sum_gradient_y ** 2)
            return out

        def gaussian_blur(image, kernel):
            out = np.zeros(image.shape)
            rows, cols = image.shape

            # Blur radius;
            diameter = kernel.shape[0]
            radius = diameter // 2

            # Flatten image and kernel;
            image_1d = image.reshape(-1)
            kernel_1d = kernel.reshape(-1)

            for i in range(rows):
                for j in range(cols):
                    sum_tmp = 0
                    for x in range(-radius, radius + 1):
                        for y in range(-radius, radius + 1):
                            nx = x + i
                            ny = y + j
                            if (nx >= 0 and ny >= 0 and nx < rows and ny < cols):
                                sum_tmp += kernel_1d[(x + radius) * diameter + (y + radius)] * image_1d[nx * cols + ny]
                    out[i, j] = sum_tmp
            return out

        def normalize(image):
            return (image - np.min(image)) / (np.max(image) - np.min(image))

        def truncate(image, minimum=0, maximum=1):
            out = image.copy()
            out[out < minimum] = minimum
            out[out > maximum] = maximum
            return out

        # Recompute the CPU result only if necessary;
        start = time.time()
        if self.current_iter == 0 or reinit:
            # Part 1: Small blur on medium frequencies;
            blurred_small = gaussian_blur(self.image_cpu, self.kernel_small_cpu)
            edges_small = sobel_filter(blurred_small)

            # Part 2: High blur on low frequencies;
            blurred_large = gaussian_blur(self.image_cpu, self.kernel_large_cpu)
            edges_large = sobel_filter(blurred_large)
            # Extend mask to cover a larger area;
            edges_large = normalize(edges_large) * 5
            edges_large[edges_large > 1] = 1

            # Part 3: Sharpen image;
            unsharpen = gaussian_blur(self.image_cpu, self.kernel_unsharpen_cpu)
            amount = 0.5
            sharpened = truncate(self.image_cpu * (1 + amount) - unsharpen * amount)

            # Part 4: Merge sharpened image and low frequencies;
            image2 = normalize(sharpened * edges_large + blurred_large * (1 - edges_large))

            # Part 5: Merge image and medium frequencies;
            self.cpu_result = image2 * edges_small + blurred_small * (1 - edges_small)

        cpu_time = time.time() - start

        # Compare GPU and CPU results;
        difference = 0
        for i in range(self.size):
            for j in range(self.size):
                difference += np.abs(self.cpu_result[i, j] - gpu_result[i, j])

        self.benchmark.add_to_benchmark("cpu_time_sec", cpu_time)
        self.benchmark.add_to_benchmark("cpu_gpu_res_difference", str(difference))
        if self.benchmark.debug:
            BenchmarkResult.log_message(f"\tcpu result: [" + ", ".join([f"{x:.4f}" for x in self.cpu_result[0, :10]])
                                        + "...]; " +
                                        f"difference: {difference:.4f}, time: {cpu_time:.4f} sec")
