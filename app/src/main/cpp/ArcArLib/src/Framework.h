#pragma once

#define AA_VERSION "2.2.1"

#include <stdint.h>
#include <iostream>
#include <string>
#include <vector>
#include <algorithm>
#include <sstream>
#include <fstream>
#include <iomanip>
#include <chrono>
#include <queue>
#include <deque>
#include <stack>
#include <cassert>
#include <map>
#include <set>
#include <unordered_set>
#include <unordered_map>
#include <list>
#include <functional>
#include <chrono>
#include <filesystem>
#include <random>
#include <mutex>
#include <bit>
#include <thread>
#include <cstring>
#include <array>

#define _USE_MATH_DEFINES // for M_PI and similar
#include <cmath>
#include <math.h>

#ifdef _MSC_VER
// Disable annoying truncation warnings on MSVC
#pragma warning(disable: 4305 4244 4267)
#endif

typedef uint8_t byte;

// Current millisecond time
#define AA_CUR_MS() (std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count())

#define AA_MAX(a, b) ((a > b) ? a : b)
#define AA_MIN(a, b) ((a < b) ? a : b)

#define AA_CLAMP(val, min, max) AA_MIN(AA_MAX(val, min), max)

#ifndef AA_DONT_LOG
#define AA_LOG(s) { std::cout << std::dec << s << std::endl; }
#else
#define AA_LOG(s) {}
#endif

#define AA_LOG_BLANK() AA_LOG("")

#define AA_STR(s) ([&]{ std::stringstream __macroStream; __macroStream << s; return __macroStream.str(); }())

// Returns sign of number (1 if positive, -1 if negative, and 0 if 0)
#define AA_SGN(val) ((val > 0) - (val < 0))

#define AA_WARN(s) AA_LOG("ARCAR WARNING: " << s)

#define AA_ERR_CLOSE(s) { \
	std::string _errorStr = AA_STR("ARCAR FATAL ERROR: " << s); \
	AA_LOG(_errorStr); \
	throw std::runtime_error(_errorStr); \
	exit(EXIT_FAILURE); \
}

#define AA_ALIGN_16 alignas(16)

#ifndef AA_NO_NAMESPACE
#define AA_NS_START namespace ArcAr {
#define AA_NS_END }
#else
#define AA_NS_START
#define AA_NS_END
#endif

template<typename ...Args>
size_t __AA_GET_ARGUMENT_COUNT(Args ...) {
	return sizeof...(Args);
}
#define AA_GET_ARGUMENT_COUNT __AA_GET_ARGUMENT_COUNT

constexpr uint32_t __AA_GET_VERSION_ID() {
	uint32_t result = 0;
	for (int i = 0; i < sizeof(AA_VERSION); i++)
		result = AA_MAX(AA_VERSION[i] - '0' + 1, 0) + (result*10);
	return result;
}
#define AA_VERSION_ID (__AA_GET_VERSION_ID())

#define AA_IS_BIG_ENDIAN (std::endian::native == std::endian::big)
