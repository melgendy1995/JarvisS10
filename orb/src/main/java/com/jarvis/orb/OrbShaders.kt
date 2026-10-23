package com.jarvis.orb

/**
 * GLES 3.0 port of the vertex/fragment pair in docs/jarvis-ui.html.
 * Points are additive (`GL_ONE, GL_ONE`); the fragment keeps brightness in RGB and alpha at 1.
 */
internal object OrbShaders {
    val vertex = """
        #version 300 es
        precision highp float;
        precision highp int;

        in vec4 a;
        uniform float uT, uR, uSw, uN, uSc, uLvl, uLo, uHi, uPs, uAsp, uDpr, uB, uBloom, uFlat, uFlick, uMaxPs;
        uniform vec2 uDrift;
        out float vA;
        out float vH;

        vec3 hash3(float n) {
            return fract(sin(vec3(n, n + 1.7, n + 3.1)) * 43758.5453);
        }

        vec3 field(vec3 p, float t) {
            return vec3(
                sin(p.y * 2.1 + t) + sin(p.z * 3.3 - t * 0.7),
                sin(p.z * 1.9 - t * 0.8) + sin(p.x * 2.7 + t * 0.5),
                sin(p.x * 2.3 + t * 0.6) + sin(p.y * 3.1 - t * 0.9)
            ) * 0.5;
        }

        void main() {
            vec3 p = a.xyz;
            float r = a.w;
            float t = uT;
            float ang = t * (0.10 + uSw * 0.30) * (0.7 + 0.6 * r) + uSw * (1.6 - abs(p.y)) * length(p.xz) * 1.4;
            p.y *= 1.0 - uFlat * (0.55 + 0.25 * r);
            float c = cos(ang);
            float s = sin(ang);
            p.xz = mat2(c, -s, s, c) * p.xz;
            float tilt = 0.35 + 0.1 * sin(t * 0.05);
            float ct = cos(tilt);
            float st = sin(tilt);
            p.yz = mat2(ct, -st, st, ct) * p.yz;
            float breathe = 1.0 + 0.035 * sin(t * 0.9) + 0.015 * sin(t * 2.3 + r * 6.0);
            p += field(p * 1.4 + r, t * (0.6 + uSw)) * uN;
            float band = sin(p.y * 7.0 - t * 6.0) * 0.5 + 0.5;
            float push = uLvl * (0.10 + 0.22 * band * uLo + 0.18 * uHi * r);
            p *= uR * breathe * (1.0 + push);
            vec3 h = hash3(r * 991.0) * 2.0 - 1.0;
            p += h * uSc * (0.9 + 0.4 * sin(t * 0.2 + r * 10.0));
            float z = p.z + 3.2;
            vec2 sp = p.xy / (z * 0.42);
            sp.x /= uAsp;
            gl_Position = vec4(sp * 0.70 + vec2(0.0, 0.07) + uDrift, 0.0, 1.0);
            float depth = clamp((4.4 - z) / 2.4, 0.0, 1.0);
            float size = uDpr * uPs * (2.2 + 6.0 * depth) * (1.0 + uLvl * 0.7 * band) * (1.0 + uBloom * 4.0);
            gl_PointSize = clamp(size, 1.0, uMaxPs);
            float flick = 1.0 - uFlick * (0.5 + 0.5 * sin(t * 11.0 + r * 47.0));
            vA = uB * (0.35 + 0.9 * depth) * (0.5 + 0.5 * fract(r * 13.7)) * (1.0 + uLvl * 1.1 * band) * (1.0 - uBloom * 0.93) * flick;
            vH = band * uLvl + r * 0.3;
        }
    """.trimIndent()

    val fragment = """
        #version 300 es
        precision mediump float;

        uniform vec3 uC;
        uniform vec3 uBase;
        uniform float uCm;
        uniform vec3 uTint;
        in float vA;
        in float vH;
        out vec4 fragColor;

        void main() {
            vec2 d = gl_PointCoord - vec2(0.5);
            float q = dot(d, d) * 4.0;
            if (q > 1.0) discard;
            float g = exp(-q * 3.2) + 0.25 * exp(-q * 12.0);
            vec3 c = mix(uBase, uC, clamp(uCm + vH * 0.25, 0.0, 1.0)) * uTint;
            c = mix(c, vec3(0.85, 0.95, 1.0), 0.18 * exp(-q * 14.0));
            fragColor = vec4(c * g * vA, 1.0);
        }
    """.trimIndent()
}
