package com.example.javbrowser.nativeapp

import com.example.javbrowser.nativeapp.data.*
import com.example.javbrowser.nativeapp.domain.*
import org.junit.Assert.*
import org.junit.Test

class CoreDomainTest {
    @Test fun normalizesCommonCodes() {
        assertEquals("SONE-123", JavIdentity.normalize(" sone_123 "))
        assertEquals("FC2-PPV-3175924", JavIdentity.normalize("https://x.test/fc2ppv-3175924"))
        assertEquals("IPX-001", JavIdentity.extract("IPX%2D001-chinese-subtitle"))
    }

    @Test fun deduplicatesOnlyHighConfidenceCodeMatches() {
        val a=SourceRef("missav","a");val b=SourceRef("jable","b")
        val merged=JavMerger.deduplicate(listOf(
            JavSearchResult(JavTitle("SONE-123","SONE-123","One",sourceRefs=listOf(a)),a),
            JavSearchResult(JavTitle("sone-123","sone-123","One alt",sourceRefs=listOf(b)),b),
            JavSearchResult(JavTitle("x",null,"One",sourceRefs=listOf(SourceRef("javdb","x"))),SourceRef("javdb","x"))
        ))
        assertEquals(2,merged.size);assertEquals(2,merged.first{it.code!=null}.sourceRefs.size)
    }

    @Test fun metadataMergeKeepsGoodPrimaryAndFillsMissing() {
        val actor=JavEntity("a","Actor",EntityType.ACTOR)
        val merged=JavMerger.merge(JavTitle("A","A-1","Primary",coverUrl="cover"),JavTitle("A","A-1","Secondary",coverUrl="",actors=listOf(actor),rating=4.5))
        assertEquals("Primary",merged.title);assertEquals("cover",merged.coverUrl);assertEquals(actor,merged.actors.single());assertEquals(4.5,merged.rating!!,0.0)
    }

    @Test fun ranksPreferredThenQuality() {
        val low=PlaybackVariant("preferred","720p","a",720,StreamType.HLS)
        val high=PlaybackVariant("other","1080p","b",1080,StreamType.HLS)
        assertEquals(low,PlaybackRanker.rank(listOf(high,low),"preferred").first())
        assertEquals(high,PlaybackRanker.rank(listOf(low,high)).first())
    }

    @Test fun detectsSourceUrlsAndCacheExpiry() {
        assertEquals("jable",SourceUrlDetector.detect("https://jable.tv/videos/abc"));assertNull(SourceUrlDetector.detect("https://example.com"))
        assertTrue(CachePolicy.isFresh(100,150,100));assertFalse(CachePolicy.isFresh(100,200,100))
    }

    @Test fun migratesUnresolvedLegacyRecordWithoutDroppingIt() {
        val items=LegacyMigration.favorites("""[{"title":"Old bookmark","url":"https://unknown.example/item"}]""")
        assertEquals(1,items.size);assertEquals("legacy",items.single().sourceRefs.single().sourceId)
    }
}
