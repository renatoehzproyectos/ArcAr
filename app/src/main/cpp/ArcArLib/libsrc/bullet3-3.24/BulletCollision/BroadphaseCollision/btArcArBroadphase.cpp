/*
Bullet Continuous Collision Detection and Physics Library
Copyright (c) 2003-2006 Erwin Coumans  https://bulletphysics.org

This software is provided 'as-is', without any express or implied warranty.
In no event will the authors be held liable for any damages arising from the use of this software.
Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it freely,
subject to the following restrictions:

1. The origin of this software must not be misrepresented; you must not claim that you wrote the original software. If you use this software in a product, an acknowledgment in the product documentation would be appreciated but is not required.
2. Altered source versions must be plainly marked as such, and must not be misrepresented as being the original software.
3. This notice may not be removed or altered from any source distribution.
*/

#include "btArcArBroadphase.h"
#include "btDispatcher.h"
#include "btCollisionAlgorithm.h"

#include "../../LinearMath/btVector3.h"
#include "../../LinearMath/btTransform.h"
#include "../../LinearMath/btMatrix3x3.h"
#include "../../LinearMath/btAabbUtil2.h"

#include "../CollisionShapes/btBvhTriangleMeshShape.h"

#include <new>
#include <string>
#include <stdexcept>
#include <iostream>
#include <mutex>

#define THROW_ERR(msg) { std::string fullMsg = std::string() + "btArcArBroadphase fatal error: " msg; std::cout << msg << std::endl; throw std::runtime_error(fullMsg); }

void btArcArBroadphase::validate() {
	for (int i = 0; i < m_numHandles; i++) {
		for (int j = i + 1; j < m_numHandles; j++) {
			btAssert(&m_pHandles[i] != &m_pHandles[j]);
		}
	}
}

btArcArBroadphase::btArcArBroadphase(btVector3 min, btVector3 max, float cellSize, btOverlappingPairCache* overlappingPairCache, int maxProxies)
	: m_pairCache(overlappingPairCache),
	m_ownsPairCache(false),
	m_invalidPair(0) {

	if (!overlappingPairCache)
		THROW_ERR("overlappingPairCache is NULL");

	// allocate handles buffer and put all handles on free list
	m_pHandlesRawPtr = btAlignedAlloc(sizeof(btArcArBroadphaseProxy) * maxProxies, 16);
	m_pHandles = new (m_pHandlesRawPtr) btArcArBroadphaseProxy[maxProxies];
	m_maxHandles = maxProxies;
	m_numHandles = 0;
	m_firstFreeHandle = 0;
	m_LastHandleIndex = -1;

	{
		for (int i = m_firstFreeHandle; i < maxProxies; i++) {
			m_pHandles[i].SetNextFree(i + 1);
			m_pHandles[i].m_uniqueId = i + 2;  //any UID will do, we just avoid too trivial values (0,1) for debugging purposes
		}
		m_pHandles[maxProxies - 1].SetNextFree(0);
	}

	// Build grid
	minPos = min;
	maxPos = max;
	if (!(minPos < maxPos))
		THROW_ERR("Invalid minPos and maxPos, (minPos < maxPos) failed");

	btVector3 range = maxPos - minPos;

	this->cellSize = cellSize;
	this->cellSizeSq = cellSize * cellSize;

	cellsX = btMax(1, (int)ceil(range.x() / cellSize));
	cellsY = btMax(1, (int)ceil(range.y() / cellSize));
	cellsZ = btMax(1, (int)ceil(range.z() / cellSize));
	totalCells = cellsX * cellsY * cellsZ;

	cells = std::vector<Cell>(totalCells);
}

btArcArBroadphase::~btArcArBroadphase() {
	btAlignedFree(m_pHandlesRawPtr);

	if (m_ownsPairCache) {
		m_pairCache->~btOverlappingPairCache();
		btAlignedFree(m_pairCache);
	}
}

template <bool ADD>
void _UpdateCellsStatic(btArcArBroadphase* _this, btArcArBroadphaseProxy* proxy) {

	// Fix dumb massive value aabb bug
	btVector3 aabbMax = proxy->m_aabbMax;
	for (int i = 0; i < 3; i++)
		aabbMax[i] = btMin(aabbMax[i], _this->maxPos[i]);

	int iMin, jMin, kMin;
	int iMax, jMax, kMax;
	_this->GetCellIndices(proxy->m_aabbMin, iMin, jMin, kMin);
	_this->GetCellIndices(aabbMax, iMax, jMax, kMax);

	btCollisionObject* colObj = (btCollisionObject*)proxy->m_clientObject;

	// We should check if each cell actually collides with the object
	bool isTriMesh = colObj && colObj->m_collisionShape->getShapeType() == TRIANGLE_MESH_SHAPE_PROXYTYPE;

	// For checking if an AABB has any containing triangles
	struct BoolHitTriangleCallback : public btTriangleCallback {

		bool hit = false;

		BoolHitTriangleCallback() {}
		virtual void processTriangle(btVector3* triangle, int partId, int triangleIndex) {
			hit = true;
		}
	};
	BoolHitTriangleCallback callbackInst = {};

	int numSkipped = 0;
	for (int i = iMin; i <= iMax; i++) {
		for (int j = jMin; j <= jMax; j++) {
			for (int k = kMin; k <= kMax; k++) {
				std::vector<btArcArBroadphase::Cell*> cells = {};
				for (int i1 = -1; i1 <= 1; i1++) {
					for (int j1 = -1; j1 <= 1; j1++) {
						for (int k1 = -1; k1 <= 1; k1++) {
							int ci = i + i1;
							int cj = j + j1;
							int ck = k + k1;
							if (ci < 0 || cj < 0 || ck < 0)
								continue;
							if (ci >= _this->cellsX || cj >= _this->cellsY || ck >= _this->cellsZ)
								continue;
							cells.push_back(&_this->GetCell(ci, cj, ck));
						}
					}
				}
				
				if (isTriMesh) {
					auto triMeshShape = (btTriangleMeshShape*)colObj->m_collisionShape;
					btVector3 cellMin = _this->GetCellMinPos(i, j, k);
					btVector3 cellMax = cellMin + btVector3(_this->cellSize, _this->cellSize, _this->cellSize);

					callbackInst.hit = false;
					triMeshShape->processAllTriangles(&callbackInst, cellMin, cellMax);

					if (!callbackInst.hit) {
						numSkipped++;

						if (ADD) {
							continue; // No tris in this AABB, ignore
						} else {
							// Remove it anyway
						}
					}
				}

				for (auto cell : cells) {
					if (ADD) {
						bool alreadyExists = false;
						for (auto staticHandle : cell->staticHandles) {
							if (staticHandle == proxy) {
								alreadyExists = true;
								break;
							}
						}
						if (!alreadyExists)
							cell->staticHandles.push_back(proxy);
					} else {
						cell->RemoveStatic(proxy);
					}
				}
			}
		}
	}
}

template <bool ADD>
void _UpdateCellsDynamic(btArcArBroadphase* _this, btArcArBroadphaseProxy* proxy, int ci, int cj, int ck) {

	int mni = btMax(0, ci - 1), mnj = btMax(0, cj - 1), mnk = btMax(0, ck - 1);
	int mxi = btMin(_this->cellsX - 1, ci + 1), mxj = btMin(_this->cellsY - 1, cj + 1), mxk = btMin(_this->cellsZ - 1, ck + 1);

	for (int ci = mni; ci <= mxi; ci++) {
		for (int cj = mnj; cj <= mxj; cj++) {
			for (int ck = mnk; ck <= mxk; ck++) {
				auto& cell = _this->GetCell(ci, cj, ck);
				if (ADD) {
					cell.dynHandles.push_back(proxy);
				} else {
					cell.RemoveDyn(proxy);
				}
			}
		}
	}
}

btBroadphaseProxy* btArcArBroadphase::createProxy(const btVector3& aabbMin, const btVector3& aabbMax, int shapeType, void* userPtr, int collisionFilterGroup, int collisionFilterMask, btCollisionDispatcher* /*dispatcher*/) {
	if (m_numHandles >= m_maxHandles) {
		THROW_ERR("Max handles exceeded when creating proxy");
	}
	btAssert(aabbMin[0] <= aabbMax[0] && aabbMin[1] <= aabbMax[1] && aabbMin[2] <= aabbMax[2]);

	auto* obj = (btCollisionObject*)(userPtr);
	bool isStatic = obj->isStaticObject();

	int newHandleIndex = allocHandle();
	int cellIdx = GetCellIdx(aabbMin);
	int iIdx, jIdx, kIdx;
	GetCellIndices(aabbMin, iIdx, jIdx, kIdx);

	btArcArBroadphaseProxy* proxy = new (&m_pHandles[newHandleIndex]) btArcArBroadphaseProxy(
		aabbMin, aabbMax, shapeType, userPtr, collisionFilterGroup, collisionFilterMask, 
		isStatic, 
		cellIdx, iIdx, jIdx, kIdx
	);

	if (isStatic) {
		_UpdateCellsStatic<true>(this, proxy);

	} else {
		if (aabbMin.distance2(aabbMax) > cellSizeSq)
			THROW_ERR("Dynamic object's AABB size exceeds maximum cell size (" + std::to_string(aabbMin.distance(aabbMax)) + " > " + std::to_string(cellSize) + ")");

		_UpdateCellsDynamic<true>(this, proxy, iIdx, jIdx, kIdx);
		numDynProxies++;
	}

	return proxy;
}

class RemovingOverlapCallback : public btOverlapCallback
{
protected:
	virtual bool processOverlap(btBroadphasePair& pair) {
		(void)pair;
		btAssert(0);
		return false;
	}
};

class RemovePairContainingProxy
{
	btBroadphaseProxy* m_targetProxy;

public:
	virtual ~RemovePairContainingProxy() {
	}

protected:
	virtual bool processOverlap(btBroadphasePair& pair) {
		btArcArBroadphaseProxy* proxy0 = static_cast<btArcArBroadphaseProxy*>(pair.m_pProxy0);
		btArcArBroadphaseProxy* proxy1 = static_cast<btArcArBroadphaseProxy*>(pair.m_pProxy1);

		return ((m_targetProxy == proxy0 || m_targetProxy == proxy1));
	};
};

void btArcArBroadphase::destroyProxy(btBroadphaseProxy* proxyOrg, btCollisionDispatcher* dispatcher) {
	btArcArBroadphaseProxy* sbp = getArcArProxyFromProxy(proxyOrg);
	m_pairCache->removeOverlappingPairsContainingProxy(proxyOrg, dispatcher);
	
	if (sbp->isStatic) {
		_UpdateCellsStatic<false>(this, sbp);
	} else {
		_UpdateCellsDynamic<false>(this, sbp, sbp->iIdx, sbp->jIdx, sbp->kIdx);
		numDynProxies--;
	}

	btArcArBroadphaseProxy* proxy0 = static_cast<btArcArBroadphaseProxy*>(proxyOrg);
	freeHandle(proxy0);
}

void btArcArBroadphase::getAabb(btBroadphaseProxy* proxy, btVector3& aabbMin, btVector3& aabbMax) const {
	const btArcArBroadphaseProxy* sbp = getArcArProxyFromProxy(proxy);
	aabbMin = sbp->m_aabbMin;
	aabbMax = sbp->m_aabbMax;
}

void btArcArBroadphase::setAabb(btBroadphaseProxy* proxy, const btVector3& aabbMin, const btVector3& aabbMax, btCollisionDispatcher* /*dispatcher*/) {
	btArcArBroadphaseProxy* sbp = getArcArProxyFromProxy(proxy);
	
	if (sbp->m_aabbMin != aabbMin || sbp->m_aabbMax != aabbMax) {
		if (sbp->isStatic) {
			_UpdateCellsStatic<false>(this, sbp);

			sbp->m_aabbMin = aabbMin;
			sbp->m_aabbMax = aabbMax;

			_UpdateCellsStatic<true>(this, sbp);
		} else {

			int oldIndex = sbp->cellIdx;
			sbp->m_aabbMin = aabbMin;
			sbp->m_aabbMax = aabbMax;

			int newIndex = GetCellIdx(aabbMin);
			sbp->cellIdx = newIndex;

			if (oldIndex != newIndex) {

				if (numDynProxies > 1) {
					_UpdateCellsDynamic<false>(this, sbp, sbp->iIdx, sbp->jIdx, sbp->kIdx);

					// TODO: Can determine newIndex from these
					int iNew, jNew, kNew;
					GetCellIndices(aabbMin, iNew, jNew, kNew);
					sbp->iIdx = iNew;
					sbp->jIdx = jNew;
					sbp->kIdx = kNew;

					_UpdateCellsDynamic<true>(this, sbp, iNew, jNew, kNew);
				}
			}
		}
	}
}

void btArcArBroadphase::rayTest(const btVector3& rayFrom, const btVector3& rayTo, btBroadphaseRayCallback& rayCallback, const btVector3& aabbMin, const btVector3& aabbMax) {
	float rayLenSq = rayFrom.distance2(rayTo);

	if (rayLenSq < cellSizeSq) {

		Cell& cell = cells[GetCellIdx(rayFrom)];
		for (auto& otherProxy : cell.staticHandles)
			if (otherProxy->m_clientObject)
				rayCallback.process(otherProxy);
		for (auto& otherProxy : cell.dynHandles)
			if (otherProxy->m_clientObject)
				rayCallback.process(otherProxy);
	} else {
		static std::once_flag onceFlag;
		std::call_once(onceFlag, 
			[this]() {
				std::cout <<
					"[!] btArcArBroadphase WARNING:" <<
					"\nRay casts in ArcAr that are longer than " << this->cellSize << "uu are very expensive and not properly optimized." <<
					"\nIf you have a project that requires these long rays, consider implementing proper DDA for the custom voxel broadphase." <<
					std::endl;
			}
		);

		for (int i = 0; i <= m_LastHandleIndex; i++) {
			btArcArBroadphaseProxy* proxy = &m_pHandles[i];
			if (!proxy->m_clientObject) {
				continue;
			}
			rayCallback.process(proxy);
		}
	}
}

void btArcArBroadphase::aabbTest(const btVector3& aabbMin, const btVector3& aabbMax, btBroadphaseAabbCallback& callback) {
	// TODO: Optimize

	for (int i = 0; i <= m_LastHandleIndex; i++) {
		btArcArBroadphaseProxy* proxy = &m_pHandles[i];
		if (!proxy->m_clientObject)
			continue;
		
		if (TestAabbAgainstAabb2(aabbMin, aabbMax, proxy->m_aabbMin, proxy->m_aabbMax)) {
			callback.process(proxy);
		}
	}
}

bool btArcArBroadphase::aabbOverlap(btArcArBroadphaseProxy* proxy0, btArcArBroadphaseProxy* proxy1) {
	return TestAabbAgainstAabb2(proxy0->m_aabbMin, proxy0->m_aabbMax, proxy1->m_aabbMin, proxy1->m_aabbMax);
}

//then remove non-overlapping ones
class CheckOverlapCallback : public btOverlapCallback
{
public:
	virtual bool processOverlap(btBroadphasePair& pair) {
		return (!btArcArBroadphase::aabbOverlap(static_cast<btArcArBroadphaseProxy*>(pair.m_pProxy0), static_cast<btArcArBroadphaseProxy*>(pair.m_pProxy1)));
	}
};

std::string ToStr(const btVector3& vec) {
	std::stringstream stream;
	stream << "[ " << vec.x() << ", " << vec.y() << ", " << vec.z() << " ]";
	return stream.str();
}

void btArcArBroadphase::calculateOverlappingPairs(btCollisionDispatcher* dispatcher) {

	int lastRealPairs = totalRealPairs;

	bool shouldRemove = !m_pairCache->hasDeferredRemoval();

	if (shouldRemove) {
		for (auto pair : activePairs) {
			m_pairCache->removeOverlappingPair(pair.first, pair.second, dispatcher);
		}
		activePairs.clear();
	} else {
		THROW_ERR("Pair cache cannot have deferred removal");
	}

	if (m_numHandles >= 0) {

		int new_largest_index = -1;
		for (int i = 0; i <= m_LastHandleIndex; i++) {
			btArcArBroadphaseProxy* proxy = &m_pHandles[i];
			if (proxy->isStatic)
				continue; // TODO: Use separate list

			if (!proxy->m_clientObject)
				continue;
			
			totalItrs++;

			new_largest_index = i;

			Cell& cell = cells[proxy->cellIdx];
			
			for (auto& otherProxy : cell.staticHandles) {
				if (!otherProxy->m_clientObject)
					continue;

				totalStaticPairs++;

				
				if (aabbOverlap(proxy, otherProxy)) {
					if (!m_pairCache->findPair(proxy, otherProxy)) {
						m_pairCache->addOverlappingPair(proxy, otherProxy);
						activePairs.push_back({ proxy, otherProxy });
						totalRealPairs++;
					}
				}
			}

			if (numDynProxies > 1) {
				if (cell.dynHandles.size() > 1) { // We are dynamic, so there will always be 1
					for (auto& otherProxy : cell.dynHandles) {
						if (otherProxy == proxy)
							continue;

						if (!otherProxy->m_clientObject)
							continue;

						totalDynPairs++;

						if (aabbOverlap(proxy, otherProxy)) {
							if (!m_pairCache->findPair(proxy, otherProxy)) {
								m_pairCache->addOverlappingPair(proxy, otherProxy);
								activePairs.push_back({ proxy, otherProxy });
								totalRealPairs++;
							}
						}
					}
				}
			}
		}

		m_LastHandleIndex = new_largest_index;

		if (m_ownsPairCache)
			THROW_ERR("Cannot own pair cache!");
	}
}

bool btArcArBroadphase::testAabbOverlap(btBroadphaseProxy* proxy0, btBroadphaseProxy* proxy1) {
	btArcArBroadphaseProxy* p0 = getArcArProxyFromProxy(proxy0);
	btArcArBroadphaseProxy* p1 = getArcArProxyFromProxy(proxy1);
	return aabbOverlap(p0, p1);
}

void btArcArBroadphase::resetPool(btCollisionDispatcher* dispatcher) {
	// TODO: ?
}
