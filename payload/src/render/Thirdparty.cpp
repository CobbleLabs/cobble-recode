/* Single translation unit for the header-only dependencies.
 *
 * stb_truetype rasterises glyph SDFs; nanosvg parses and rasterises the Lucide
 * icon set. Both are compiled once here so their warnings never leak into
 * Cobble Recode's own /W4 build. */

#if defined(_MSC_VER)
#pragma warning(push, 0)
#endif

#define STB_TRUETYPE_IMPLEMENTATION
#include "stb_truetype.h"

#define NANOSVG_IMPLEMENTATION
#define NANOSVG_ALL_COLOR_KEYWORDS
#include "nanosvg.h"

#define NANOSVGRAST_IMPLEMENTATION
#include "nanosvgrast.h"

#if defined(_MSC_VER)
#pragma warning(pop)
#endif
