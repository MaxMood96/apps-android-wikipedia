package org.wikipedia.search;

import org.wikipedia.ApiTask;
import org.wikipedia.PageTitle;
import org.wikipedia.Site;
import org.wikipedia.WikipediaApp;
import org.mediawiki.api.json.Api;
import org.mediawiki.api.json.ApiException;
import org.mediawiki.api.json.ApiResult;
import org.mediawiki.api.json.RequestBuilder;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class FullSearchArticlesTask extends ApiTask<FullSearchArticlesTask.FullSearchResults> {
    private final Site site;
    private final String searchTerm;
    private final int maxResults;
    private final ContinueOffset continueOffset;

    public FullSearchArticlesTask(Api api, Site site, String searchTerm, int maxResults, ContinueOffset continueOffset) {
        super(LOW_CONCURRENCY, api);
        this.site = site;
        this.searchTerm = searchTerm;
        this.maxResults = maxResults;
        this.continueOffset = continueOffset;
    }

    @Override
    public RequestBuilder buildRequest(Api api) {
        final String maxResultsString = Integer.toString(maxResults);
        final RequestBuilder req = api.action("query")
                .param("prop", "pageprops|pageimages")
                .param("ppprop", "wikibase_item") // only interested in wikibase_item
                .param("generator", "search")
                .param("gsrsearch", searchTerm)
                .param("gsrnamespace", "0")
                .param("gsrwhat", "text")
                .param("gsrinfo", "")
                .param("gsrprop", "redirecttitle")
                .param("gsrlimit", maxResultsString)
                .param("piprop", "thumbnail") // for thumbnail URLs
                .param("pithumbsize", Integer.toString(WikipediaApp.PREFERRED_THUMB_SIZE))
                .param("pilimit", maxResultsString);
        if (continueOffset != null) {
            req.param("continue", continueOffset.cont);
            if (continueOffset.gsroffset > 0) {
                req.param("gsroffset", Integer.toString(continueOffset.gsroffset));
            }
        } else {
            req.param("continue", ""); // add empty continue to avoid the API warning
        }
        return req;
    }

    @Override
    public FullSearchResults processResult(final ApiResult result) throws Throwable {
        JSONObject data;
        try {
            data = result.asObject();
        } catch (ApiException e) {
            if (e.getCause() instanceof JSONException) {
                // the only reason for a JSONException is if the response is an empty array.
                return emptyResults();
            } else {
                throw new RuntimeException(e);
            }
        }

        ContinueOffset nextContinueOffset = null;
        final JSONObject continueData = data.optJSONObject("continue");
        if (continueData != null) {
            String continueString = continueData.optString("continue", null);
            Integer gsroffset = continueData.optInt("gsroffset");
            nextContinueOffset = new ContinueOffset(continueString, gsroffset);
        }

        JSONObject queryResult = data.optJSONObject("query");
        if (queryResult == null) {
            return emptyResults();
        }

        String suggestion = "";
        JSONObject searchinfo = queryResult.optJSONObject("searchinfo");
        if (searchinfo != null) {
            if (searchinfo.has("suggestion")) {
                suggestion = searchinfo.getString("suggestion");
            }
        }

        JSONObject pages = queryResult.optJSONObject("pages");
        if (pages == null) {
            return emptyResults();
        }

        // The search results arrive unordered, but they do have an "index" property, which we'll
        // use to sort the results ourselves.
        // First, put all the page objects into an array
        JSONObject[] pageArray = new JSONObject[pages.length()];
        int pageIndex = 0;
        Iterator<String> pageIter = pages.keys();
        while (pageIter.hasNext()) {
            pageArray[pageIndex++] = (JSONObject)pages.get(pageIter.next());
        }
        // now sort the array based on the "index" property
        Arrays.sort(pageArray, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject lhs, JSONObject rhs) {
                int ret = 0;
                try {
                    ret = ((Integer) lhs.getInt("index")).compareTo(rhs.getInt("index"));
                } catch (JSONException e) {
                    //doesn't matter
                }
                return ret;
            }
        });
        // and create our list of results from the now-sorted array
        ArrayList<FullSearchResult> resultList = new ArrayList<FullSearchResult>();
        for (JSONObject item : pageArray) {
            PageTitle pageTitle = new PageTitle(item.getString("title"), site);
            String thumbUrl = null;
            if (item.has("thumbnail")) {
                thumbUrl = item.getJSONObject("thumbnail").optString("source", null);
            }
            String wikiBaseId = null;
            if (item.has("pageprops")) {
                wikiBaseId = item.getJSONObject("pageprops").optString("wikibase_item", null);
            }
            resultList.add(new FullSearchResult(pageTitle, thumbUrl, wikiBaseId));
        }
        return new FullSearchResults(resultList, nextContinueOffset, suggestion);
    }

    private FullSearchResults emptyResults() {
        return new FullSearchResults(Collections.<FullSearchResult>emptyList(), null, "");
    }

    public class FullSearchResults {
        private ContinueOffset continueOffset;
        private List<FullSearchResult> resultsList;
        private String suggestion;

        public FullSearchResults(List<FullSearchResult> resultList,
                                 ContinueOffset continueOffset,
                                 String suggestion) {
            this.resultsList = resultList;
            this.continueOffset = continueOffset;
            this.suggestion = suggestion;
        }

        public String getSuggestion() {
            return suggestion;
        }

        public ContinueOffset getContinueOffset() {
            return continueOffset;
        }

        public List<FullSearchResult> getResults() {
            return resultsList;
        }
    }

    public final class ContinueOffset {
        private String cont;
        private int gsroffset;

        private ContinueOffset(String cont, int gsroffset) {
            this.cont = cont;
            this.gsroffset = gsroffset;
        }
    }
}
