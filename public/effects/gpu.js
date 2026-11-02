// The picture that goes out of the webcam's effects (worker.js), made on
// the GPU in one WebGL 2 context, which MediaPipe's segmenter shares: its
// mask stays a texture there and is never read back, which cost a frame
// about 20 ms, as it waited for all the GPU had to do. As Google Meet
// does it, the mask is the segmenter's 256 x 144, made soft and eased
// from frame to frame in a pass of its own, and its edge is refined at
// the frame's size by a joint bilateral filter: near the edge, a pixel's
// share of the person is the mask's around it, weighted by how near each
// is and how like the pixel's own colour, so that the edge follows hair,
// shoulders and fingers. The background is blurred by a dual Kawase
// blur (Marius Bjørge, ARM, 2015: the frame halved four times and doubled
// twice, a few taps a pass on small textures, nearly a Gaussian at a
// fraction of its cost), each tap of the first pass weighted by how
// little of the person it holds, so that the person does not bleed into
// the blur as a halo; then one pass at the frame's size smooths the skin
// (a bilateral filter), lays the person over the blurred or flat
// background and corrects the light as the CSS filters brightness,
// contrast and saturate would.
//
// Textures are in the picture's order (the first row the top); only
// the last pass, onto the canvas, turns it over. Every draw sets the
// state it needs, since MediaPipe draws in the same context.

/** How many times the background is halved, and doubled again after. */
export const DOWN = 4;
export const UP = 2;

/**
 * The sizes of the blur's passes for a frame `width` x `height`: the
 * halvings, then the doublings back; the background is read from the
 * last one, a quarter of the frame's size (an eighth for UP = 1).
 */
export function pyramid(width, height, down = DOWN, up = UP) {
  const sizes = [];
  let w = width;
  let h = height;
  for (let i = 0; i < down; i++) {
    w = Math.max(1, Math.round(w / 2));
    h = Math.max(1, Math.round(h / 2));
    sizes.push({ width: w, height: h, kind: i === 0 ? "first" : "down" });
  }
  for (let i = 0; i < up; i++) {
    w = sizes[down - 2 - i] ? sizes[down - 2 - i].width : w * 2;
    h = sizes[down - 2 - i] ? sizes[down - 2 - i].height : h * 2;
    sizes.push({ width: w, height: h, kind: "up" });
  }
  return sizes;
}

const VERTEX = `#version 300 es
in vec2 a_pos;
out vec2 v_uv;
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}`;

// the segmenter's confidence made the mask: soft between two
// confidences, eased towards from the mask before, so that the edge does
// not flicker; `u_flip` when the source's first row is the bottom
const MASK = `#version 300 es
precision mediump float;
uniform sampler2D u_src;
uniform sampler2D u_prev;
uniform float u_low;
uniform float u_high;
uniform float u_rate;
uniform bool u_flip;
in vec2 v_uv;
out vec4 o_color;
void main() {
  vec2 uv = u_flip ? vec2(v_uv.x, 1.0 - v_uv.y) : v_uv;
  float soft = clamp((texture(u_src, uv).r - u_low) / (u_high - u_low), 0.0, 1.0);
  float prev = texture(u_prev, v_uv).r;
  o_color = vec4(mix(prev, soft, u_rate), 0.0, 0.0, 1.0);
}`;

// the first halving: from the frame, each tap weighted by the
// background it shows (1 - the mask), the weight kept in alpha
const FIRST = `#version 300 es
precision mediump float;
uniform sampler2D u_src;
uniform sampler2D u_mask;
uniform vec2 u_half;
in vec2 v_uv;
out vec4 o_color;
vec4 tap(vec2 uv) {
  float w = max(1.0 - texture(u_mask, uv).r, 0.02);
  return vec4(texture(u_src, uv).rgb * w, w);
}
void main() {
  o_color = (tap(v_uv) * 4.0
    + tap(v_uv - u_half)
    + tap(v_uv + u_half)
    + tap(v_uv + vec2(u_half.x, -u_half.y))
    + tap(v_uv - vec2(u_half.x, -u_half.y))) / 8.0;
}`;

// a halving of the dual Kawase blur
const DOWN_PASS = `#version 300 es
precision mediump float;
uniform sampler2D u_src;
uniform vec2 u_half;
in vec2 v_uv;
out vec4 o_color;
void main() {
  o_color = (texture(u_src, v_uv) * 4.0
    + texture(u_src, v_uv - u_half)
    + texture(u_src, v_uv + u_half)
    + texture(u_src, v_uv + vec2(u_half.x, -u_half.y))
    + texture(u_src, v_uv - vec2(u_half.x, -u_half.y))) / 8.0;
}`;

// a doubling of the dual Kawase blur
const UP_PASS = `#version 300 es
precision mediump float;
uniform sampler2D u_src;
uniform vec2 u_half;
in vec2 v_uv;
out vec4 o_color;
void main() {
  vec2 h = u_half;
  o_color = (texture(u_src, v_uv + vec2(-h.x * 2.0, 0.0))
    + texture(u_src, v_uv + vec2(-h.x, h.y)) * 2.0
    + texture(u_src, v_uv + vec2(0.0, h.y * 2.0))
    + texture(u_src, v_uv + vec2(h.x, h.y)) * 2.0
    + texture(u_src, v_uv + vec2(h.x * 2.0, 0.0))
    + texture(u_src, v_uv + vec2(h.x, -h.y)) * 2.0
    + texture(u_src, v_uv + vec2(0.0, -h.y * 2.0))
    + texture(u_src, v_uv + vec2(-h.x, -h.y)) * 2.0) / 12.0;
}`;

// the picture: the person's edge refined (a joint bilateral filter on the
// mask, its weights the frame's colours), the skin smoothed (a bilateral
// filter: each skin pixel the average of the neighbours near it in place
// and in colour, so that a pore or a shadow fades while an eyelash or the
// edge of the face stays sharp), the light corrected, the person over the
// background; a flat background is left as it is, for the green to key
const FINAL = `#version 300 es
precision mediump float;
uniform sampler2D u_frame;
uniform sampler2D u_skin;
uniform sampler2D u_mask;
uniform sampler2D u_bg;
uniform vec2 u_texel;
uniform vec2 u_mtexel;
uniform float u_step;
uniform float u_range;
uniform float u_strength;
uniform int u_background;
uniform vec3 u_color;
uniform vec3 u_light;
uniform bool u_down;
in vec2 v_uv;
out vec4 o_color;
vec3 lit(vec3 c) {
  c = clamp(c * u_light.x, 0.0, 1.0);
  c = clamp((c - 0.5) * u_light.y + 0.5, 0.0, 1.0);
  float s = u_light.z;
  mat3 saturate = mat3(
    0.213 + 0.787 * s, 0.213 - 0.213 * s, 0.213 - 0.213 * s,
    0.715 - 0.715 * s, 0.715 + 0.285 * s, 0.715 - 0.715 * s,
    0.072 - 0.072 * s, 0.072 - 0.072 * s, 0.072 + 0.928 * s);
  return clamp(saturate * c, 0.0, 1.0);
}
float coarse(vec2 uv) {
  return texture(u_mask, uv).r;
}
// the person's share of the pixel at uv of colour c: the mask as it is
// away from the edge, near it the mask around, each sample weighted by
// its distance (in the mask's pixels) and by how like c the frame is there
float person(vec2 uv, vec3 c) {
  float m = coarse(uv);
  vec2 d = u_mtexel * 2.0;
  float a = coarse(uv + d);
  float b = coarse(uv - d);
  float e = coarse(uv + vec2(d.x, -d.y));
  float f = coarse(uv - vec2(d.x, -d.y));
  float lo = min(min(min(a, b), min(e, f)), m);
  float hi = max(max(max(a, b), max(e, f)), m);
  if (hi - lo < 0.04) return m;
  float sum = 0.0;
  float weights = 0.0;
  for (int y = -2; y <= 2; y++) {
    for (int x = -2; x <= 2; x++) {
      vec2 o = vec2(float(x), float(y));
      vec2 at = uv + o * u_mtexel;
      vec3 s = texture(u_frame, at).rgb - c;
      float w = exp(-dot(o, o) / 4.5) * exp(-dot(s, s) * 40.0);
      sum += coarse(at) * w;
      weights += w;
    }
  }
  return sum / weights;
}
void main() {
  // on the canvas its first row is the picture's bottom, read back its top
  vec2 uv = vec2(v_uv.x, u_down ? v_uv.y : 1.0 - v_uv.y);
  vec3 c = texture(u_frame, uv).rgb;
  float share = u_background != 0 ? smoothstep(0.0, 1.0, person(uv, c)) : 1.0;
  float m = texture(u_skin, uv).a * u_strength;
  if (m >= 0.01) {
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    float inv = 1.0 / (2.0 * u_range * u_range);
    for (int y = -3; y <= 3; y++) {
      for (int x = -3; x <= 3; x++) {
        vec2 d = vec2(float(x), float(y));
        vec3 s = texture(u_frame, uv + d * u_step * u_texel).rgb;
        vec3 diff = s - c;
        float w = exp(-dot(d, d) / 8.0) * exp(-dot(diff, diff) * inv);
        sum += s * w;
        weights += w;
      }
    }
    c = mix(c, sum / weights, m);
  }
  c = lit(c);
  if (u_background != 0) {
    vec3 back = u_color;
    if (u_background == 1) {
      vec4 b = texture(u_bg, uv);
      back = lit(b.rgb / max(b.a, 0.001));
    }
    c = mix(back, c, share);
  }
  o_color = vec4(c, 1.0);
}`;

// the picture read back as NV12, the format the encoders take: the
// brightness of four pixels of a row in one RGBA pixel, a plane of a
// byte a pixel; then the colour of two 2 x 2 blocks in one, U and V in
// turn, a plane of half the rows; BT.709, limited range, as HD video is
const PACK_Y = `#version 300 es
precision highp float;
uniform sampler2D u_src;
out vec4 o_color;
float luma(int x, int y) {
  vec3 c = texelFetch(u_src, ivec2(x, y), 0).rgb;
  return (16.0 + 219.0 * dot(c, vec3(0.2126, 0.7152, 0.0722))) / 255.0;
}
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  int x = p.x * 4;
  o_color = vec4(luma(x, p.y), luma(x + 1, p.y), luma(x + 2, p.y), luma(x + 3, p.y));
}`;

const PACK_UV = `#version 300 es
precision highp float;
uniform sampler2D u_src;
out vec4 o_color;
vec2 chroma(int x, int y) {
  vec3 c = (texelFetch(u_src, ivec2(x, y), 0).rgb + texelFetch(u_src, ivec2(x + 1, y), 0).rgb
    + texelFetch(u_src, ivec2(x, y + 1), 0).rgb + texelFetch(u_src, ivec2(x + 1, y + 1), 0).rgb) * 0.25;
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  return (128.0 + 224.0 * vec2((c.b - l) / 1.8556, (c.r - l) / 1.5748)) / 255.0;
}
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  o_color = vec4(chroma(p.x * 4, p.y * 2), chroma(p.x * 4 + 2, p.y * 2));
}`;

/** The colours of the NV12 pictures, as PACK_Y and PACK_UV make them. */
const NV12_COLOR = { primaries: "bt709", transfer: "bt709", matrix: "bt709", fullRange: false };

function shader(gl, type, source) {
  const s = gl.createShader(type);
  gl.shaderSource(s, source);
  gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
    throw new Error("the shader did not compile: " + gl.getShaderInfoLog(s));
  }
  return s;
}

function program(gl, fragment, samplers) {
  const p = gl.createProgram();
  gl.attachShader(p, shader(gl, gl.VERTEX_SHADER, VERTEX));
  gl.attachShader(p, shader(gl, gl.FRAGMENT_SHADER, fragment));
  gl.bindAttribLocation(p, 0, "a_pos");
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
    throw new Error("the program did not link: " + gl.getProgramInfoLog(p));
  }
  gl.useProgram(p);
  samplers.forEach((name, unit) => gl.uniform1i(gl.getUniformLocation(p, name), unit));
  const uniforms = {};
  const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
  for (let i = 0; i < n; i++) {
    const name = gl.getActiveUniform(p, i).name;
    uniforms[name] = gl.getUniformLocation(p, name);
  }
  return { program: p, uniforms };
}

function texture(gl) {
  const t = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, t);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  return t;
}

function target(gl, t) {
  const fb = gl.createFramebuffer();
  gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
  gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
  return fb;
}

/**
 * The compositor on `canvas` (an OffscreenCanvas whose WebGL 2 context
 * MediaPipe's segmenter may share), for a mask of `maskWidth` x
 * `maskHeight`: `resize(width, height)` for the frames' size, then per
 * frame a new mask (`maskFrom`, `maskFromBytes`) and `draw(frame,
 * picture)`, after which `canvas` holds the picture to make a VideoFrame
 * of; or, live, `draw(frame, {...picture, read: true})` and then
 * `pictures()`, the pictures drawn before read back into memory as
 * VideoFrames. Throws without WebGL 2.
 */
export function compositor(canvas, maskWidth, maskHeight) {
  const gl = canvas.getContext("webgl2", {
    alpha: false,
    antialias: false,
    depth: false,
    premultipliedAlpha: false,
    preserveDrawingBuffer: true,
  });
  if (!gl) throw new Error("no WebGL 2");
  // half floats keep the weighted colours of the blur where the
  // weights are small, behind the person; bytes where the GPU cannot
  const floats = Boolean(gl.getExtension("EXT_color_buffer_float") || gl.getExtension("EXT_color_buffer_half_float"));

  // our own vertex array: MediaPipe binds its own
  const vao = gl.createVertexArray();
  gl.bindVertexArray(vao);
  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.bindVertexArray(null);

  const maskProgram = program(gl, MASK, ["u_src", "u_prev"]);
  const first = program(gl, FIRST, ["u_src", "u_mask"]);
  const down = program(gl, DOWN_PASS, ["u_src"]);
  const up = program(gl, UP_PASS, ["u_src"]);
  const final = program(gl, FINAL, ["u_frame", "u_skin", "u_mask", "u_bg"]);
  const packY = program(gl, PACK_Y, ["u_src"]);
  const packUV = program(gl, PACK_UV, ["u_src"]);

  const frameTexture = texture(gl);
  const skinTexture = texture(gl);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(4));
  // the segmenter's confidences as bytes, where it ran on the CPU
  const rawTexture = texture(gl);
  gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, maskWidth, maskHeight, 0, gl.RED, gl.UNSIGNED_BYTE, null);
  // the mask, eased: the one drawn into and the one before, in turn
  const masks = [0, 1].map(() => {
    const t = texture(gl);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, maskWidth, maskHeight, 0, gl.RED, gl.UNSIGNED_BYTE, null);
    return { texture: t, framebuffer: target(gl, t) };
  });
  let current = 0;
  let maskSeen = false;

  // the mask read back for the light, without waiting: a buffer, a fence
  const pack = gl.createBuffer();
  let fence = null;
  let read = null;

  let width = 0;
  let height = 0;
  let passes = [];

  // the pictures read back into memory, live, without waiting: drawn into
  // a texture of their own, packed as NV12 (a third of the bytes of RGBA;
  // RGBA where the size is not a multiple of 4 x 2), copied into buffers
  // in turn, each with a fence, and taken once the GPU is done with
  // them, a frame later. A
  // VideoFrame of the canvas lives on the GPU, and the encoder of the
  // recording (VP8, in software) read each back at once, waiting for the
  // GPU every frame; the Intel GPU, seeing little to do, slowed down,
  // and the webcam fell to 3 frames a second.
  const READS = 4;
  const reads = Array.from({ length: READS }, () => ({ buffer: gl.createBuffer(), size: 0, fence: null }));
  const pending = [];
  let readNext = 0;
  let readsDropped = 0;
  let picture = null;
  let pixels = null;

  // the GPU's time of a picture, where the browser lets it be measured;
  // read later, without waiting
  const timing = gl.getExtension("EXT_disjoint_timer_query_webgl2");
  const timer = {
    queries: [],
    current: null,
    total: 0,
    count: 0,
    begin() {
      if (!timing || this.current) return;
      this.current = gl.createQuery();
      gl.beginQuery(timing.TIME_ELAPSED_EXT, this.current);
    },
    end() {
      if (!this.current) return;
      gl.endQuery(timing.TIME_ELAPSED_EXT);
      this.queries.push(this.current);
      this.current = null;
      while (this.queries.length && gl.getQueryParameter(this.queries[0], gl.QUERY_RESULT_AVAILABLE)) {
        const q = this.queries.shift();
        if (!gl.getParameter(timing.GPU_DISJOINT_EXT)) {
          this.total += gl.getQueryParameter(q, gl.QUERY_RESULT) / 1e6;
          this.count++;
        }
        gl.deleteQuery(q);
      }
    },
    take() {
      if (!timing) return null;
      const ms = this.count ? Math.round((this.total / this.count) * 10) / 10 : null;
      this.total = 0;
      this.count = 0;
      return ms;
    },
  };

  /** Every pass of the blur in textures of the frames' size. */
  function resize(w, h) {
    if (w === width && h === height) return;
    width = w;
    height = h;
    for (const pass of passes) {
      gl.deleteTexture(pass.texture);
      gl.deleteFramebuffer(pass.framebuffer);
    }
    passes = pyramid(width, height).map((size) => {
      const t = texture(gl);
      if (floats) gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, size.width, size.height, 0, gl.RGBA, gl.HALF_FLOAT, null);
      else gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, size.width, size.height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      return { ...size, texture: t, framebuffer: target(gl, t) };
    });
    if (picture) {
      for (const t of [picture, picture.y, picture.uv]) {
        if (!t) continue;
        gl.deleteTexture(t.texture);
        gl.deleteFramebuffer(t.framebuffer);
      }
      picture = null;
    }
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }

  /** A texture of `w` x `h` to draw into, with its framebuffer. */
  function canvasOf(w, h) {
    const t = texture(gl);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    return { texture: t, framebuffer: target(gl, t) };
  }

  /** The texture a picture to be read back is drawn into, and its NV12 planes where the size allows. */
  function pictureTarget() {
    if (!picture) {
      picture = canvasOf(width, height);
      if (width % 4 === 0 && height % 2 === 0) {
        picture.y = canvasOf(width / 4, height);
        picture.uv = canvasOf(width / 4, height / 2);
      }
    }
    return picture.framebuffer;
  }

  /** The picture packed into the plane `into` by `p` (PACK_Y, PACK_UV). */
  function packPlane(p, into, w, h) {
    gl.useProgram(p.program);
    gl.bindFramebuffer(gl.FRAMEBUFFER, into.framebuffer);
    gl.viewport(0, 0, w, h);
    bind(0, picture.texture);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  /** The picture just drawn copied into the next buffer, a fence after it; none when all are waiting. */
  function readPicture(timestamp) {
    if (pending.length >= READS) {
      readsDropped++;
      return;
    }
    const r = reads[readNext];
    readNext = (readNext + 1) % READS;
    const nv12 = Boolean(picture.y);
    const size = nv12 ? (width * height * 3) / 2 : width * height * 4;
    // as packed as can be, whatever MediaPipe, in this context too, left
    gl.pixelStorei(gl.PACK_ALIGNMENT, 4);
    gl.pixelStorei(gl.PACK_ROW_LENGTH, 0);
    gl.pixelStorei(gl.PACK_SKIP_PIXELS, 0);
    gl.pixelStorei(gl.PACK_SKIP_ROWS, 0);
    gl.bindBuffer(gl.PIXEL_PACK_BUFFER, r.buffer);
    if (r.size !== size) {
      // not a READ usage: Chrome would shadow-copy such a buffer at every
      // fence and, as ours is read through the fence it waits for itself,
      // throw the copy away with a warning each frame; measured, either
      // costs the same
      gl.bufferData(gl.PIXEL_PACK_BUFFER, size, gl.STREAM_COPY);
      r.size = size;
    }
    if (nv12) {
      packPlane(packY, picture.y, width / 4, height);
      gl.readBuffer(gl.COLOR_ATTACHMENT0);
      gl.readPixels(0, 0, width / 4, height, gl.RGBA, gl.UNSIGNED_BYTE, 0);
      packPlane(packUV, picture.uv, width / 4, height / 2);
      gl.readBuffer(gl.COLOR_ATTACHMENT0);
      gl.readPixels(0, 0, width / 4, height / 2, gl.RGBA, gl.UNSIGNED_BYTE, width * height);
    } else {
      gl.readBuffer(gl.COLOR_ATTACHMENT0);
      gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, 0);
    }
    gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null);
    r.fence = gl.fenceSync(gl.SYNC_GPU_COMMANDS_COMPLETE, 0);
    r.nv12 = nv12;
    r.width = width;
    r.height = height;
    r.timestamp = timestamp;
    pending.push(r);
  }

  /** The state every pass of ours needs, whatever MediaPipe left. */
  function ready() {
    gl.bindVertexArray(vao);
    gl.disable(gl.BLEND);
    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.STENCIL_TEST);
    gl.disable(gl.SCISSOR_TEST);
    gl.disable(gl.CULL_FACE);
    gl.colorMask(true, true, true, true);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
  }

  function done() {
    gl.bindVertexArray(null);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }

  function bind(unit, t) {
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, t);
  }

  /** A new mask from the segmenter's confidences in `source`, a texture. */
  function maskFrom(source, { low, high, rate, flip }) {
    ready();
    const into = masks[1 - current];
    const prev = masks[current];
    gl.useProgram(maskProgram.program);
    gl.bindFramebuffer(gl.FRAMEBUFFER, into.framebuffer);
    gl.viewport(0, 0, maskWidth, maskHeight);
    bind(0, source);
    bind(1, prev.texture);
    const u = maskProgram.uniforms;
    gl.uniform1f(u.u_low, low);
    gl.uniform1f(u.u_high, high);
    gl.uniform1f(u.u_rate, maskSeen ? rate : 1);
    gl.uniform1i(u.u_flip, flip ? 1 : 0);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    current = 1 - current;
    maskSeen = true;
    done();
  }

  // a copy of the frame when the GPU does not take a VideoFrame as it is
  let copy = null;
  function upload(frame) {
    try {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, frame);
    } catch (_error) {
      if (!copy || copy.width !== width || copy.height !== height) copy = new OffscreenCanvas(width, height);
      copy.getContext("2d").drawImage(frame, 0, 0, width, height);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, copy);
    }
  }

  /** The background blurred into the passes; the last one holds it. */
  function blur() {
    let source = { texture: frameTexture };
    for (const pass of passes) {
      const p = pass.kind === "first" ? first : pass.kind === "down" ? down : up;
      gl.useProgram(p.program);
      gl.bindFramebuffer(gl.FRAMEBUFFER, pass.framebuffer);
      gl.viewport(0, 0, pass.width, pass.height);
      bind(0, source.texture);
      if (pass.kind === "first") bind(1, masks[current].texture);
      // half a texel of the pass written (Bjørge's halfpixel)
      gl.uniform2f(p.uniforms.u_half, 0.5 / pass.width, 0.5 / pass.height);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      source = pass;
    }
    return source.texture;
  }

  return {
    canvas,
    gl,
    resize,
    /**
     * A new mask from the segmenter's confidence `texture` (its own, in
     * this context): soft between `low` and `high`, `rate` of it eased in;
     * `flip` when its first row is the picture's bottom.
     */
    maskFrom,
    /** A new mask from the segmenter's confidences as `bytes` (0..255, the picture's order). */
    maskFromBytes(bytes, options) {
      ready();
      bind(0, rawTexture);
      gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, maskWidth, maskHeight, gl.RED, gl.UNSIGNED_BYTE, bytes);
      done();
      maskFrom(rawTexture, { ...options, flip: false });
    },
    /**
     * The mask as bytes (the picture's order, 255 the person), read back
     * without waiting: the last one whose read is done, or null; a new
     * read is begun when none is under way.
     */
    readMask() {
      if (fence) {
        const status = gl.clientWaitSync(fence, 0, 0);
        if (status === gl.ALREADY_SIGNALED || status === gl.CONDITION_SATISFIED) {
          gl.deleteSync(fence);
          fence = null;
          const bytes = new Uint8Array(maskWidth * maskHeight * 4);
          gl.bindBuffer(gl.PIXEL_PACK_BUFFER, pack);
          gl.getBufferSubData(gl.PIXEL_PACK_BUFFER, 0, bytes);
          gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null);
          read = new Uint8Array(maskWidth * maskHeight);
          for (let i = 0; i < read.length; i++) read[i] = bytes[i * 4];
        }
      } else if (maskSeen) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, masks[current].framebuffer);
        gl.bindBuffer(gl.PIXEL_PACK_BUFFER, pack);
        gl.bufferData(gl.PIXEL_PACK_BUFFER, maskWidth * maskHeight * 4, gl.STREAM_READ);
        gl.readPixels(0, 0, maskWidth, maskHeight, gl.RGBA, gl.UNSIGNED_BYTE, 0);
        gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null);
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        fence = gl.fenceSync(gl.SYNC_GPU_COMMANDS_COMPLETE, 0);
      }
      return read;
    },
    /** Whether a mask was made. */
    get maskSeen() {
      return maskSeen;
    },
    /** A new skin: an image whose alpha is where the smoothing goes. */
    skin(image) {
      ready();
      bind(1, skinTexture);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
      done();
    },
    /**
     * The picture of `frame` on the canvas. `picture`: `background`
     * ("off", "blur", "green", "black"), `color` (its RGB, 0..1, for a
     * flat one), `light` ({brightness, contrast, saturate}), `touchUp`
     * (the skin's level, {strength, range, step}, or null); `read`: not on the canvas but read back, `pictures()` gives it
     * later with `timestamp`, as NV12.
     */
    draw(frame, { background, color, light, touchUp, read = false, timestamp = 0 }) {
      timer.begin();
      if (!read && (canvas.width !== width || canvas.height !== height)) {
        canvas.width = width;
        canvas.height = height;
      }
      ready();
      bind(0, frameTexture);
      upload(frame);
      const mode = background === "blur" ? 1 : background === "off" ? 0 : 2;
      const bg = mode === 1 ? blur() : null;
      gl.bindFramebuffer(gl.FRAMEBUFFER, read ? pictureTarget() : null);
      gl.viewport(0, 0, width, height);
      gl.useProgram(final.program);
      const u = final.uniforms;
      bind(0, frameTexture);
      bind(1, skinTexture);
      bind(2, masks[current].texture);
      bind(3, bg || frameTexture);
      gl.uniform2f(u.u_texel, 1 / width, 1 / height);
      gl.uniform2f(u.u_mtexel, 1 / maskWidth, 1 / maskHeight);
      gl.uniform1f(u.u_strength, touchUp ? touchUp.strength : 0);
      gl.uniform1f(u.u_range, touchUp ? touchUp.range : 1);
      gl.uniform1f(u.u_step, touchUp ? touchUp.step * (width / 1280) : 0);
      gl.uniform1i(u.u_background, mode);
      gl.uniform3f(u.u_color, ...(color || [0, 0, 0]));
      gl.uniform3f(u.u_light, light.brightness, light.contrast, light.saturate);
      gl.uniform1i(u.u_down, read ? 1 : 0);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      if (read) readPicture(timestamp);
      timer.end();
      done();
    },
    /**
     * The pictures drawn with `read` whose copies are done, in order, as
     * VideoFrames in memory; without waiting, so none while the GPU is
     * still at the first.
     */
    pictures() {
      const out = [];
      while (pending.length) {
        const r = pending[0];
        const status = gl.clientWaitSync(r.fence, 0, 0);
        if (status !== gl.ALREADY_SIGNALED && status !== gl.CONDITION_SATISFIED) break;
        pending.shift();
        gl.deleteSync(r.fence);
        r.fence = null;
        if (!pixels || pixels.length !== r.size) pixels = new Uint8Array(r.size);
        gl.bindBuffer(gl.PIXEL_PACK_BUFFER, r.buffer);
        gl.getBufferSubData(gl.PIXEL_PACK_BUFFER, 0, pixels);
        gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null);
        // the frame copies the bytes: the array serves the next one
        const shape = { codedWidth: r.width, codedHeight: r.height, timestamp: r.timestamp };
        out.push(
          r.nv12
            ? new VideoFrame(pixels, { ...shape, format: "NV12", colorSpace: NV12_COLOR })
            : new VideoFrame(pixels, { ...shape, format: "RGBX" })
        );
      }
      return out;
    },
    /** The pictures under way forgotten: the effects went off, they would come late. */
    forget() {
      for (const r of pending) {
        gl.deleteSync(r.fence);
        r.fence = null;
      }
      pending.length = 0;
    },
    /**
     * The context's error since asked last, by name, or null: asked once
     * a second, as it waits for the GPU.
     */
    error() {
      const e = gl.getError();
      if (e === gl.NO_ERROR) return null;
      const names = { [gl.INVALID_ENUM]: "INVALID_ENUM", [gl.INVALID_VALUE]: "INVALID_VALUE", [gl.INVALID_OPERATION]: "INVALID_OPERATION", [gl.INVALID_FRAMEBUFFER_OPERATION]: "INVALID_FRAMEBUFFER_OPERATION", [gl.OUT_OF_MEMORY]: "OUT_OF_MEMORY", [gl.CONTEXT_LOST_WEBGL]: "CONTEXT_LOST" };
      return names[e] || String(e);
    },
    /** The pictures not read back since asked last, all buffers waiting. */
    readsDropped() {
      const n = readsDropped;
      readsDropped = 0;
      return n;
    },
    /** The GPU's milliseconds of the pictures measured since asked last, each; null where it cannot tell. */
    gpuTime() {
      return timer.take();
    },
  };
}
