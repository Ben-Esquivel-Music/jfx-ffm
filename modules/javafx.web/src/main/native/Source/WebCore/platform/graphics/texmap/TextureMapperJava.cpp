/*
 * Copyright (c) 2018, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include "config.h"
#include "TextureMapper.h"

#if USE(TEXTURE_MAPPER)

#include "AffineTransform.h"
#include "BitmapTexture.h"
#include "BitmapTexturePool.h"
#include "ClipPath.h"
#include "ClipStack.h"
#include "Color.h"
#include "DestinationColorSpace.h"
#include "FilterOperations.h"
#include "FloatPoint.h"
#include "FloatQuad.h"
#include "FloatRect.h"
#include "FloatRoundedRect.h"
#include "FloatSize.h"
#include "GraphicsContext.h"
#include "GraphicsTypes.h"
#include "ImageBuffer.h"
#include "ImagePaintingOptions.h"
#include "IntRect.h"
#include "IntSize.h"
#include "Path.h"
#include "PixelFormat.h"
#include "PlatformContextJava.h"
#include "RenderingMode.h"
#include "TextureMapperGPUBuffer.h"
#include "TransformationMatrix.h"
#include "WindRule.h"
#include <array>
#include <cmath>
#include <optional>
#include <stdint.h>
#include <utility>
#include <wkj_constants.h>
#include <wtf/MathExtras.h>
#include <wtf/RefPtr.h>
#include <wtf/TZoneMallocInlines.h>
#include <wtf/Vector.h>

namespace WebCore {

WTF_MAKE_TZONE_ALLOCATED_IMPL(TextureMapper);

static std::optional<AffineTransform> textureMapperJavaPlaneTransform(const TransformationMatrix& matrix)
{
    if (matrix.m14() || matrix.m24() || matrix.m44() <= 0)
        return std::nullopt;
    double w = matrix.m44();
    return AffineTransform(matrix.m11() / w, matrix.m12() / w, matrix.m21() / w, matrix.m22() / w,
        matrix.m41() / w, matrix.m42() / w);
}

// The scissor box covers every pixel the clip lets through, so a plane-affine draw that misses it draws
// nothing. Every surface tile replays the whole layer subtree, which makes this cull worth its cost; a true
// projection is not culled.
static bool textureMapperJavaIsOutsideClip(const TransformationMatrix& matrix, const FloatRect& rect,
    const IntRect& clipBounds)
{
    auto planeTransform = textureMapperJavaPlaneTransform(matrix);
    return planeTransform && !enclosingIntRect(planeTransform->mapRect(rect)).intersects(clipBounds);
}

static void textureMapperJavaConcatModelView(GraphicsContext& context, const TransformationMatrix& matrix)
{
    if (auto planeTransform = textureMapperJavaPlaneTransform(matrix)) {
        context.concatCTM(*planeTransform);
        return;
    }

    // The mapper draws z = 0 quads only, so x, y and w do not depend on the z output. Zeroing it stands in
    // for GL's depth range: no camera can clip the quad in z.
    TransformationMatrix projection(matrix);
    projection.setM13(0);
    projection.setM23(0);
    projection.setM33(1);
    projection.setM43(0);
    context.platformContext()->rq().freeSpace(68)
        << static_cast<int32_t>(com_sun_webkit_graphics_GraphicsDecoder_SET_PERSPECTIVE_TRANSFORM)
        << static_cast<float>(projection.m11()) << static_cast<float>(projection.m12())
        << static_cast<float>(projection.m13()) << static_cast<float>(projection.m14())
        << static_cast<float>(projection.m21()) << static_cast<float>(projection.m22())
        << static_cast<float>(projection.m23()) << static_cast<float>(projection.m24())
        << static_cast<float>(projection.m31()) << static_cast<float>(projection.m32())
        << static_cast<float>(projection.m33()) << static_cast<float>(projection.m34())
        << static_cast<float>(projection.m41()) << static_cast<float>(projection.m42())
        << static_cast<float>(projection.m43()) << static_cast<float>(projection.m44());
}

static Path textureMapperJavaPolygonPath(const Vector<FloatPoint>& vertices, const TransformationMatrix& matrix)
{
    Path path;
    bool isFirst = true;
    for (auto& vertex : vertices) {
        if (isFirst)
            path.moveTo(matrix.mapPoint(vertex));
        else
            path.addLineTo(matrix.mapPoint(vertex));
        isFirst = false;
    }
    path.closeSubpath();
    return path;
}

static Vector<FloatPoint> textureMapperJavaRectVertices(const FloatRect& rect)
{
    return { rect.minXMinYCorner(), rect.maxXMinYCorner(), rect.maxXMaxYCorner(), rect.minXMaxYCorner() };
}

static Vector<FloatPoint> textureMapperJavaRoundedRectVertices(const FloatRoundedRect& roundedRect)
{
    constexpr unsigned segmentsPerCorner = 8;
    struct Corner {
        FloatSize radius;
        FloatPoint center;
        double startAngle;
    };

    const FloatRect& rect = roundedRect.rect();
    const auto& radii = roundedRect.radii();
    const std::array<Corner, 4> corners { {
        { radii.topLeft(), FloatPoint(rect.x() + radii.topLeft().width(), rect.y() + radii.topLeft().height()),
            2 * piOverTwoDouble },
        { radii.topRight(), FloatPoint(rect.maxX() - radii.topRight().width(), rect.y() + radii.topRight().height()),
            3 * piOverTwoDouble },
        { radii.bottomRight(),
            FloatPoint(rect.maxX() - radii.bottomRight().width(), rect.maxY() - radii.bottomRight().height()), 0 },
        { radii.bottomLeft(),
            FloatPoint(rect.x() + radii.bottomLeft().width(), rect.maxY() - radii.bottomLeft().height()),
            piOverTwoDouble },
    } };

    Vector<FloatPoint> vertices;
    vertices.reserveInitialCapacity(corners.size() * (segmentsPerCorner + 1));
    for (auto& corner : corners) {
        for (unsigned i = 0; i <= segmentsPerCorner; ++i) {
            double angle = corner.startAngle + piOverTwoDouble * i / segmentsPerCorner;
            vertices.append(FloatPoint(static_cast<float>(corner.center.x() + corner.radius.width() * std::cos(angle)),
                static_cast<float>(corner.center.y() + corner.radius.height() * std::sin(angle))));
        }
    }
    return vertices;
}

static Path textureMapperJavaRoundedRectPath(const FloatRoundedRect& roundedRect, const TransformationMatrix& matrix)
{
    auto planeTransform = textureMapperJavaPlaneTransform(matrix);
    if (!planeTransform)
        return textureMapperJavaPolygonPath(textureMapperJavaRoundedRectVertices(roundedRect), matrix);

    Path path;
    path.addRoundedRect(roundedRect);
    path.transform(*planeTransform);
    return path;
}

static RefPtr<ImageBuffer> textureMapperJavaCreateBuffer(const IntSize& size)
{
    return ImageBuffer::create(size, RenderingMode::Unaccelerated, RenderingPurpose::Unspecified, 1,
        DestinationColorSpace::SRGB(), PixelFormat::BGRA8);
}

std::unique_ptr<TextureMapper> TextureMapper::create()
{
    return makeUnique<TextureMapper>();
}

TextureMapper::TextureMapper() = default;

TextureMapper::~TextureMapper() = default;

void TextureMapper::setGraphicsContext(GraphicsContext& context, const IntRect& targetRect)
{
    m_graphicsContext = &context;
    m_targetRect = targetRect;
}

void TextureMapper::beginPainting(FlipY, BitmapTexture* surface)
{
    m_clipStack.reset(m_targetRect, ClipStack::YAxisMode::Default);
    bindSurface(surface);
}

void TextureMapper::endPainting()
{
    while (!m_clipTargets.isEmpty()) {
        auto target = m_clipTargets.takeLast();
        if (target.context)
            target.context->restore();
    }
    m_isMaskMode = false;
    m_maskBuffer = nullptr;
    m_currentSurface = nullptr;
    m_graphicsContext = nullptr;
    m_targetRect = { };
    m_clipStack = { };
    // Pool textures are acquired only while a mapper paints, so after a paint every unreferenced one is idle.
    BitmapTexturePool::singleton().releaseUnreferencedTextures();
}

ClipStack& TextureMapper::clipStack()
{
    return m_currentSurface ? m_currentSurface->clipStack() : m_clipStack;
}

TextureMapper::PaintTarget TextureMapper::currentPaintTarget()
{
    RefPtr<ImageBuffer> buffer;
    if (m_isMaskMode)
        buffer = m_maskBuffer;
    else if (m_currentSurface)
        buffer = m_currentSurface->ensureImageBuffer();
    else
        return { nullptr, m_graphicsContext };

    if (!buffer)
        return { };
    GraphicsContext* context = &buffer->context();
    return { WTF::move(buffer), context };
}

GraphicsContext* TextureMapper::pushClipTarget()
{
    auto target = currentPaintTarget();
    GraphicsContext* context = target.context;
    if (context)
        context->save();
    m_clipTargets.append(WTF::move(target));
    return context;
}

// Clips never touch the CTM: a CTM set inside an open Java clip layer would lose the layer's offset.
void TextureMapper::beginClip(const TransformationMatrix& modelViewMatrix, const FloatRoundedRect& targetRect)
{
    clipStack().push();
    GraphicsContext* context = pushClipTarget();

    // beginRoundedRectClip
    if (targetRect.isRounded() && targetRect.isRenderable() && !targetRect.isEmpty()
        && modelViewMatrix.isInvertible() && clipStack().isRoundedRectClipAllowed()) {
        IntRect scissorBox = modelViewMatrix.projectQuad(targetRect.rect()).enclosingBoundingBox();
        clipStack().addRoundedRect(targetRect, modelViewMatrix.inverse().value());
        clipStack().intersect(scissorBox);
        if (context) {
            context->clip(scissorBox);
            context->clipPath(textureMapperJavaRoundedRectPath(targetRect, modelViewMatrix), WindRule::NonZero);
        }
        return;
    }

    // beginScissorClip
    if (modelViewMatrix.isAffine()) {
        FloatQuad quad = modelViewMatrix.projectQuad(targetRect.rect());
        IntRect scissorBox = quad.enclosingBoundingBox();
        if (quad.isRectilinear() && !scissorBox.isEmpty()) {
            clipStack().intersect(scissorBox);
            if (context)
                context->clip(scissorBox);
            return;
        }
    }

    // The stencil clip of the GL beginClip.
    if (context) {
        auto quadPath = textureMapperJavaPolygonPath(textureMapperJavaRectVertices(targetRect.rect()), modelViewMatrix);
        context->clipPath(quadPath, WindRule::NonZero);
    }
}

void TextureMapper::beginClip(const TransformationMatrix& modelViewMatrix, const ClipPath& clipPath)
{
    clipStack().push();
    GraphicsContext* context = pushClipTarget();

    // The GL beginClip(ClipPath) intersects with the window viewport even while a surface is bound.
    IntRect scissorBox = modelViewMatrix.mapQuad(clipPath.bounds()).enclosingBoundingBox();
    scissorBox.intersect(m_targetRect);
    clipStack().intersect(scissorBox);
    if (context) {
        context->clip(scissorBox);
        context->clipPath(textureMapperJavaPolygonPath(clipPath.vertices(), modelViewMatrix), WindRule::NonZero);
    }
}

void TextureMapper::endClip()
{
    clipStack().pop();
    if (m_clipTargets.isEmpty())
        return;
    auto target = m_clipTargets.takeLast();
    if (target.context)
        target.context->restore();
}

IntRect TextureMapper::clipBounds()
{
    return clipStack().current().scissorBox;
}

void TextureMapper::bindSurface(BitmapTexture* surface)
{
    m_currentSurface = surface;
}

BitmapTexture* TextureMapper::currentSurface()
{
    return m_currentSurface.get();
}

void TextureMapper::drawTexture(const BitmapTexture& texture, const FloatRect& targetRect,
    const TransformationMatrix& modelViewMatrix, float opacity, AllEdgesExposed)
{
    if (clipStack().isCurrentScissorBoxEmpty())
        return;
    ImageBuffer* image = texture.imageBuffer();
    if (!image)
        return;
    FloatRect textureRect(FloatPoint::zero(), texture.size());
    TransformationMatrix matrix = modelViewMatrix * TransformationMatrix::rectToRect(textureRect, targetRect);
    if (textureMapperJavaIsOutsideClip(matrix, textureRect, clipBounds()))
        return;
    auto target = currentPaintTarget();
    if (!target.context)
        return;

    GraphicsContext& context = *target.context;
    context.save();
    textureMapperJavaConcatModelView(context, matrix);
    context.setAlpha(opacity);
    context.drawImageBuffer(*image, textureRect, ImagePaintingOptions { CompositeOperator::SourceOver });
    context.restore();
}

void TextureMapper::drawSolidColor(const FloatRect& rect, const TransformationMatrix& modelViewMatrix,
    const Color& color, bool)
{
    if (clipStack().isCurrentScissorBoxEmpty())
        return;
    if (textureMapperJavaIsOutsideClip(modelViewMatrix, rect, clipBounds()))
        return;
    auto target = currentPaintTarget();
    if (!target.context)
        return;

    GraphicsContext& context = *target.context;
    context.save();
    textureMapperJavaConcatModelView(context, modelViewMatrix);
    context.fillRect(rect, color);
    context.restore();
}

// Debug borders and repaint counters cannot be turned on in the Java port.
void TextureMapper::drawBorder(const Color&, float, const FloatRect&, const TransformationMatrix&)
{
}

void TextureMapper::drawNumber(int, const Color&, const FloatPoint&, const TransformationMatrix&)
{
}

// Prism composites DestinationIn correctly only as one draw over the whole target from its origin, so the
// mask draws are collected in a buffer of their own and applied to the surface in a single blend.
void TextureMapper::setMaskMode(bool enabled)
{
    if (enabled == m_isMaskMode)
        return;
    m_isMaskMode = enabled;

    if (enabled) {
        if (m_currentSurface)
            m_maskBuffer = textureMapperJavaCreateBuffer(m_currentSurface->size());
        return;
    }

    RefPtr<ImageBuffer> mask = std::exchange(m_maskBuffer, nullptr);
    if (!mask || !m_currentSurface)
        return;
    ImageBuffer* surfaceBuffer = m_currentSurface->ensureImageBuffer();
    if (!surfaceBuffer)
        return;

    IntRect surfaceRect(IntPoint::zero(), m_currentSurface->size());
    GraphicsContext& context = surfaceBuffer->context();
    context.save();
    context.clip(surfaceRect);
    context.drawImageBuffer(*mask, surfaceRect, ImagePaintingOptions { CompositeOperator::DestinationIn });
    context.restore();
}

IntSize TextureMapper::maxTextureSize() const
{
    return IntSize(maximumAllowedImageBufferDimension, maximumAllowedImageBufferDimension);
}

void TextureMapper::setDepthRange(double zNear, double zFar)
{
    m_zNear = zNear;
    m_zFar = zFar;
}

std::pair<double, double> TextureMapper::depthRange() const
{
    return { m_zNear, m_zFar };
}

// filtersCanBeComposited() is false on Java, so no layer reaches the mapper with filters.
RefPtr<BitmapTexture> TextureMapper::applyFilters(RefPtr<BitmapTexture>& sourceTexture, const FilterOperations&, bool)
{
    return sourceTexture;
}

// The 3D rendering context clips with ClipPath::vertices() on Java; the buffer is never filled.
Ref<TextureMapperGPUBuffer> TextureMapper::acquireBufferFromPool(size_t size, TextureMapperGPUBuffer::Type type)
{
    return TextureMapperGPUBuffer::create(size, type, TextureMapperGPUBuffer::Usage::Dynamic);
}

} // namespace WebCore

#endif // USE(TEXTURE_MAPPER)
