#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成群峦风格无缝岩石贴图（16x16），替换 poly_mech 的 block/rock/raw/*.png。
所有噪声都在环面（torus）上采样，保证上下左右无缝平铺。
"""
import numpy as np
from PIL import Image
import os, sys

SIZE = 16
OUT = "src/main/resources/assets/poly_mech/textures/block/rock/raw"

# 每种岩石：base=平均底色, dark=暗部, light=亮部, pattern=纹理类型, seed=随机种子
ROCKS = {
    "limestone":   {"base": (184, 173, 150), "dark": (150, 138, 116), "light": (214, 204, 183), "pattern": "mottle", "seed": 1},
    "shale":       {"base": ( 89,  85,  89), "dark": ( 60,  57,  60), "light": (120, 116, 120), "pattern": "layered", "seed": 2},
    "chalk":       {"base": (194, 194, 189), "dark": (168, 169, 164), "light": (220, 220, 215), "pattern": "mottle", "seed": 3},
    "chert":       {"base": (136,  85,  75), "dark": (110,  64,  56), "light": (166, 112,  98), "pattern": "banded", "seed": 4},
    "claystone":   {"base": (148, 109,  78), "dark": (118,  84,  58), "light": (178, 138, 102), "pattern": "layered", "seed": 5},
    "conglomerate":{"base": (134, 124, 102), "dark": (100,  92,  74), "light": (168, 158, 134), "pattern": "pebble", "seed": 6},
    "dolomite":    {"base": ( 79,  79,  88), "dark": ( 58,  58,  66), "light": (102, 102, 112), "pattern": "banded", "seed": 7},
    "tuff":        {"base": (108, 110, 103), "dark": ( 84,  86,  80), "light": (134, 136, 128), "pattern": "blob", "seed": 8},
    "granite":     {"base": (149, 126, 131), "dark": (120, 100, 106), "light": (180, 158, 162), "pattern": "speckle", "seed": 9},
    "basalt":      {"base": ( 57,  61,  63), "dark": ( 38,  41,  43), "light": ( 78,  83,  86), "pattern": "speckle", "seed": 10},
    "rhyolite":    {"base": (143, 133, 129), "dark": (118, 108, 104), "light": (170, 160, 156), "pattern": "speckle", "seed": 11},
    "dacite":      {"base": (136, 137, 137), "dark": (110, 111, 111), "light": (164, 165, 165), "pattern": "speckle", "seed": 12},
    "diorite":     {"base": (164, 164, 165), "dark": (136, 136, 138), "light": (196, 196, 197), "pattern": "speckle", "seed": 13},
    "gabbro":      {"base": (106, 106, 106), "dark": ( 82,  82,  82), "light": (132, 132, 132), "pattern": "speckle", "seed": 14},
    "andesite":    {"base": (106, 109, 111), "dark": ( 84,  87,  89), "light": (130, 133, 135), "pattern": "speckle", "seed": 15},
    "marble":      {"base": (221, 225, 226), "dark": (198, 202, 204), "light": (242, 245, 246), "pattern": "marble", "seed": 16},
    "gneiss":      {"base": (135, 127, 116), "dark": (106,  99,  90), "light": (166, 158, 146), "pattern": "gneiss", "seed": 17},
    "schist":      {"base": ( 99, 104,  86), "dark": ( 74,  79,  62), "light": (126, 132, 112), "pattern": "schist", "seed": 18},
    "slate":       {"base": (152, 145, 135), "dark": (120, 114, 105), "light": (184, 177, 166), "pattern": "layered", "seed": 19},
    "phyllite":    {"base": (104,  98,  97), "dark": ( 78,  73,  72), "light": (132, 126, 124), "pattern": "phyllite", "seed": 20},
    "quartzite":   {"base": (182, 171, 168), "dark": (152, 142, 138), "light": (214, 204, 200), "pattern": "speckle", "seed": 21},
}


def periodic_value_noise(size, grid, seed):
    """在环面 lattice 上双线性插值的值噪声，无缝平铺。返回 size x size 的 [0,1] 数组。"""
    rng = np.random.default_rng(seed)
    lat = rng.random((grid, grid))
    # 每个像素映射到 [0, grid) 的连续坐标
    p = (np.arange(size) + 0.5) / size * grid
    i0 = np.floor(p).astype(int) % grid
    i1 = (i0 + 1) % grid
    f = p - np.floor(p)
    # 2D bilinear: lat[y0,x0] 等
    a = lat[i0[:, None], i0[None, :]]
    b = lat[i1[:, None], i0[None, :]]
    c = lat[i0[:, None], i1[None, :]]
    d = lat[i1[:, None], i1[None, :]]
    fx = f[:, None]
    fy = f[None, :]
    v = (a * (1 - fx) * (1 - fy) +
         b * fx * (1 - fy) +
         c * (1 - fx) * fy +
         d * fx * fy)
    return v


def fbm(size, seed, octaves=3, base_grid=4):
    """多倍频周期值噪声，范围约 [0,1]。"""
    total = np.zeros((size, size))
    amp = 1.0
    norm = 0.0
    for o in range(octaves):
        total += amp * periodic_value_noise(size, base_grid * (2 ** o), seed + o * 101)
        norm += amp
        amp *= 0.55
    return total / norm


def colorize(v, rock):
    """把 [0,1] 噪声映射到 dark -> base -> light 颜色渐变。"""
    dark = np.array(rock["dark"], dtype=float)
    base = np.array(rock["base"], dtype=float)
    light = np.array(rock["light"], dtype=float)
    v = np.clip(v, 0.0, 1.0)
    # <0.5 往暗部，>0.5 往亮部
    lo = v < 0.5
    out = np.empty(v.shape + (3,), dtype=float)
    out[lo] = dark + (base - dark) * (v[lo] * 2.0)[:, None]
    out[~lo] = base + (light - base) * ((v[~lo] - 0.5) * 2.0)[:, None]
    return np.clip(out, 0, 255).astype(np.uint8)


def _detail(seed, grid, amp):
    """平滑大尺度起伏，返回 [-amp, amp] 的周期噪声。"""
    n = fbm(SIZE, seed, octaves=2, base_grid=grid)
    return (n - 0.5) * 2.0 * amp




def _detail(seed, grid, amp):
    """平滑大尺度起伏，返回 [-amp, amp] 的周期噪声。"""
    n = fbm(SIZE, seed, octaves=2, base_grid=grid)
    return (n - 0.5) * 2.0 * amp


def gen_field(rock):
    """按纹理类型生成标量场（对比度参考群峦原版：花岗岩std≈19，板岩≈7.5）。"""
    seed = rock["seed"]
    pat = rock["pattern"]
    # 基础：大尺度起伏 + 中频变化
    v = 0.5 + _detail(seed, 4, 0.16) + _detail(seed + 7, 8, 0.10)
    fine = periodic_value_noise(SIZE, 16, seed + 13)
    v = v + (fine - 0.5) * 0.08

    if pat == "speckle":
        # 清晰矿物颗粒：高频噪声阈值形成不规则团块，而非单像素噪点
        grain = periodic_value_noise(SIZE, 16, seed + 29)
        hi = grain > 0.60
        lo = grain < 0.40
        v = v + np.where(hi, 0.32, np.where(lo, -0.32, 0.0))
        # 大颗粒/斑晶
        big = fbm(SIZE, seed + 31, octaves=1, base_grid=3)
        v = v + np.where(big > 0.76, 0.22, np.where(big < 0.24, -0.22, 0.0))
    elif pat == "layered":
        layer = 0.5 + 0.5 * np.sin((np.arange(SIZE) / SIZE) * 2.0 * np.pi * 3
                                   + (fbm(SIZE, seed + 3, octaves=2, base_grid=4) - 0.5) * 6.0)
        v = v + (layer - 0.5) * 0.26
        v = v + np.where(fine > 0.80, 0.10, np.where(fine < 0.20, -0.10, 0.0))
    elif pat == "banded":
        stripe = 0.5 + 0.5 * np.sin((np.arange(SIZE) / SIZE) * 2.0 * np.pi * 2
                                    + (fbm(SIZE, seed + 3, octaves=2, base_grid=8) - 0.5) * 4.0)
        v = v + (stripe - 0.5) * 0.26
        v = v + (fine - 0.5) * 0.06
    elif pat == "mottle":
        v = v + np.where(fine > 0.72, 0.24, np.where(fine < 0.28, -0.24, 0.0))
        v = v + (fbm(SIZE, seed + 21, octaves=2, base_grid=6) - 0.5) * 0.22
    elif pat == "blob":
        blobs = fbm(SIZE, seed + 5, octaves=3, base_grid=2)
        v = np.clip(v + (blobs - 0.5) * 1.00 + (fine - 0.5) * 0.20, 0, 1)
    elif pat == "pebble":
        rng = np.random.default_rng(seed + 50)
        centers = [(int(rng.integers(0, SIZE)), int(rng.integers(0, SIZE))) for _ in range(5)]
        dist = np.full((SIZE, SIZE), 999.0)
        for cx, cy in centers:
            dx = np.abs(np.arange(SIZE) - cx)
            dx = np.minimum(dx, SIZE - dx)
            dy = np.abs(np.arange(SIZE) - cy)
            dy = np.minimum(dy, SIZE - dy)
            d = np.sqrt(dx[:, None] ** 2 + dy[None, :] ** 2)
            dist = np.minimum(dist, d)
        pebble = dist < 2.6
        inner = np.clip((dist / 2.6), 0, 1)
        v = np.where(pebble,
                     0.80 - 0.30 * inner + (fine - 0.5) * 0.06,
                     0.28 + (fine - 0.5) * 0.18)
        edge = (dist >= 2.1) & (dist < 2.6) & pebble
        v = np.where(edge, v - 0.20, v)
    elif pat == "marble":
        base = periodic_value_noise(SIZE, 2, seed)       # 大块明暗云纹
        veins = np.abs(np.sin((base - 0.5) * np.pi * 2 + (fbm(SIZE, seed + 2, octaves=1, base_grid=8) - 0.5) * 3.0))
        v = np.clip(0.5 + (base - 0.5) * 1.40 + (0.5 - veins) * 0.40 + (fine - 0.5) * 0.04, 0, 1)
    elif pat == "gneiss":
        layer = 0.5 + 0.5 * np.sin((np.arange(SIZE) / SIZE) * 2.0 * np.pi * 2
                                   + (fbm(SIZE, seed + 5, octaves=2, base_grid=2) - 0.5) * 7.0)
        v = v + (layer - 0.5) * 0.28
        grain = periodic_value_noise(SIZE, 16, seed + 11)
        v = v + np.where(grain > 0.66, 0.24, np.where(grain < 0.34, -0.24, 0.0))
    elif pat == "schist":
        layer = 0.5 + 0.5 * np.sin((np.arange(SIZE) / SIZE) * 2.0 * np.pi * 4
                                   + (fbm(SIZE, seed + 3, octaves=2, base_grid=2) - 0.5) * 6.0)
        v = v + (layer - 0.5) * 0.24
        mica = periodic_value_noise(SIZE, 16, seed + 19)
        v = v + np.where(mica > 0.70, 0.22, np.where(mica < 0.30, -0.22, 0.0))
    elif pat == "phyllite":
        layer = 0.5 + 0.5 * np.sin((np.arange(SIZE) / SIZE) * 2.0 * np.pi * 4
                                   + (fbm(SIZE, seed + 3, octaves=1, base_grid=8) - 0.5) * 4.0)
        v = v + (layer - 0.5) * 0.30 + (fine - 0.5) * 0.22
    else:
        v = v + (fine - 0.5) * 0.12

    return np.clip(v, 0, 1)

def generate():
    os.makedirs(OUT, exist_ok=True)
    for name, rock in ROCKS.items():
        v = gen_field(rock)
        img = Image.fromarray(colorize(v, rock), "RGB")
        img.save(os.path.join(OUT, name + ".png"))
        print(f"generated {name}.png avg={np.array(img).mean(axis=(0,1)).astype(int)}")


if __name__ == "__main__":
    generate()
