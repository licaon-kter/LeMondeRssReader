package org.mbach.lemonde.article;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.snackbar.Snackbar;
import com.squareup.picasso.Picasso;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.mbach.lemonde.Constants;
import org.mbach.lemonde.LeMondeDB;
import org.mbach.lemonde.R;
import org.mbach.lemonde.ThemeUtils;
import org.mbach.lemonde.home.MainActivity;
import org.mbach.lemonde.home.RssItem;

import java.util.ArrayList;
import java.util.List;

/**
 * ArticleActivity class fetch an article from an URI, and parse the HTML response.
 * Also get the comments if the article has some.
 *
 * @author Matthieu BACHELIER
 * @since 2017-05
 * @version 2.0
 */
public class ArticleActivity extends AppCompatActivity {

    private static final String TAG = "ArticleActivity";
    private static final String STATE_RECYCLER_VIEW_POS = "STATE_RECYCLER_VIEW_POS";
    private static final String STATE_RECYCLER_VIEW = "STATE_RECYCLER_VIEW";
    private static final String STATE_ADAPTER_ITEM = "STATE_ADAPTER_ITEM";

    private String commentsURI;
    private String shareSubject;
    private String shareText;
    private boolean isRestricted = false;

    @Nullable
    static RequestQueue REQUEST_QUEUE = null;

    private final ArticleAdapter articleAdapter = new ArticleAdapter();
    private final AlphaAnimation animationFadeIn = new AlphaAnimation(0, 1);
    private RecyclerView recyclerView;
    private ProgressBar autoLoader;
    private MenuItem shareItem;
    private MenuItem toggleFavItem;

    @Nullable
    private String shareLink;

    /**
     * See @articleReceived field.
     */
    @Nullable
    private final Response.ErrorListener errorResponse = new Response.ErrorListener() {
        @Override
        public void onErrorResponse(VolleyError error) {
            findViewById(R.id.articleLoader).setVisibility(View.GONE);

            ConnectivityManager cm = (ConnectivityManager) getBaseContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting()) {
                    // Display icon
                    findViewById(R.id.noNetwork).setVisibility(View.VISIBLE);
                    // Display permanent message
                    Snackbar.make(findViewById(R.id.coordinatorArticle), getString(R.string.error_no_connection), Snackbar.LENGTH_INDEFINITE)
                            .setAction(getString(R.string.error_no_connection_retry), new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if (REQUEST_QUEUE != null) {
                                        REQUEST_QUEUE.add(new StringRequest(Request.Method.GET, shareLink, articleReceived, errorResponse));
                                    }
                                }
                            }).show();
                }
            }
        }
    };
    /**
     * See @articleReceived field.
     */
    private final Response.Listener<String> commentsReceived = new Response.Listener<String>() {
        @Override
        public void onResponse(String response) {
            Document commentDoc = Jsoup.parse(response);

            List<Model> items = new ArrayList<>();
            if (commentsURI.endsWith("contributions")) {
                // Extract header
                Element header = commentDoc.getElementById("comments-header");
                TextView commentHeader = new TextView(ArticleActivity.this);
                commentHeader.setText(header.text());
                commentHeader.setTypeface(null, Typeface.BOLD);
                commentHeader.setPadding(0, 0, 0, Constants.PADDING_COMMENT_ANSWER);
                items.add(new Model(Model.TEXT_TYPE, commentHeader, 0));
            }

            // Extract comments
            Element commentsRiver = commentDoc.getElementById("comments-river");
            for (Element comment : commentsRiver.children()) {
                CommentModel commentModel = new CommentModel();
                Elements elementsAuthor = comment.select(".comment__header .comment__author");
                if (atLeastOneChild(elementsAuthor)) {
                    commentModel.setAuthor(elementsAuthor.first().text());
                }
                Elements elementsDate = comment.select(".comment__header .comment__date");
                if (atLeastOneChild(elementsDate)) {
                    commentModel.setDate(elementsDate.first().text());
                }
                Elements elementsContent = comment.select(".comment__content");
                if (atLeastOneChild(elementsContent)) {
                    commentModel.setContent(elementsContent.first().text());
                }
                items.add(commentModel);
            }
            // Extract next page in the pagination bloc
            Elements nextLink = commentDoc.select("li.pagination__item.pagination__item--active + li.pagination__item a.pagination__link");
            if (atLeastOneChild(nextLink)) {
                commentsURI = nextLink.first().attr("href");
            } else {
                commentsURI = "";
            }
            articleAdapter.addItems(items);
        }
    };

    /**
     * This listener is a callback which can parse and extract the HTML page that has been received after
     * an asynchronous call to the web. Jsoup library is used to parse the response and not to make the call.
     * Otherwise, a NetworkOnMainThreadException will be fired by the system.
     */
    @Nullable
    private final Response.Listener<String> articleReceived = new Response.Listener<String>() {
        @Override
        public void onResponse(String response) {
            // Hide icon
            findViewById(R.id.noNetwork).setVisibility(View.INVISIBLE);

            Document doc = Jsoup.parse(response);
            Elements metas = doc.select("meta[property=og:article:section]");
            if (atLeastOneChild(metas)) {
                setTitle(metas.first().attr("content"));
            }

            // If article was loaded from an external App, no image was passed from MainActivity,
            // so it must be fetched in the Collapsing Toolbar
            if (Intent.ACTION_VIEW.equals(getIntent().getAction())) {
                Elements image = doc.select("meta[property=og:image]");
                if (atLeastOneChild(image)) {
                    Picasso.with(ArticleActivity.this)
                            .load(image.first().attr("content"))
                            .into((ImageView) findViewById(R.id.imageArticle));
                }
            }

            // Full article is restricted to paid members
            Elements articleStatus = doc.select(".article__status");
            if (!articleStatus.select(".icon__premium").isEmpty()) {
                isRestricted = true;
                articleStatus.remove();
            }

            // Standard article
            List<Model> items = new ArticleHtmlParser(ArticleActivity.this).parse(doc);
            LeMondeDB leMondeDB = new LeMondeDB(ArticleActivity.this);
            //Log.d(TAG, "shareLink " + shareLink);
            boolean hasArticle = leMondeDB.hasArticle(shareLink);
            toggleFavIcon(hasArticle);
            if (isRestricted) {
                if (shareItem != null) {
                    shareItem.setIcon(getResources().getDrawable(R.drawable.ic_share_black));
                }
                CollapsingToolbarLayout collapsingToolbar = findViewById(R.id.collapsing_toolbar);
                collapsingToolbar.setContentScrimResource(R.color.accent);
                collapsingToolbar.setCollapsedTitleTextColor(getResources().getColor(R.color.primary_dark));
                setTagInHeader(R.string.paid_article, R.color.accent, Color.BLACK);

                if (getSupportActionBar() != null) {
                    final Drawable upArrow = getResources().getDrawable(R.drawable.ic_arrow_back_black_24dp);
                    getSupportActionBar().setHomeAsUpIndicator(upArrow);
                }

                // Add a button before comments where the user can connect
                CardView connectButton = new CardView(ArticleActivity.this);
                items.add(new Model(Model.BUTTON_TYPE, connectButton, 0));
            } else if (getIntent().getExtras() != null) {
                String subtype = getIntent().getStringExtra(Constants.EXTRA_RSS_SUBTYPE);
                if ("live".equals(subtype)) {
                    setTagInHeader(R.string.live_article, R.color.accent_live, Color.WHITE);
                } else if ("video".equals(subtype)) {
                    setTagInHeader(R.string.video_article, R.color.accent_complementary, Color.WHITE);
                }
            }
            // After parsing the article, start a new request for comments
            Elements commentsElements = doc.select(".article__reactions .comments__active");
            if (atLeastOneChild(commentsElements)) {
                commentsURI = commentsElements.first().attr("href");
                if (REQUEST_QUEUE != null) {
                    REQUEST_QUEUE.add(new StringRequest(Request.Method.GET, commentsURI, commentsReceived, errorResponse));
                }
            }
            articleAdapter.addItems(items);
            findViewById(R.id.articleLoader).setVisibility(View.GONE);
        }
    };

    /**
     * Check if elements has at least one child.
     * This helper is useful because Elements.select() returns a collection of nodes.
     *
     * @param elements nodes to check
     * @return true if elements can be safely called with first()
     */
    public static boolean atLeastOneChild(@Nullable Elements elements) {
        return elements != null && !elements.isEmpty();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent motionEvent) {
        try {
            return super.dispatchTouchEvent(motionEvent);
        } catch (NullPointerException e) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtils.applyTheme(getBaseContext(), getTheme());
        setContentView(R.layout.activity_article);
        setTitle("");

        final LinearLayoutManager linearLayoutManager = new LinearLayoutManager(this);
        recyclerView = findViewById(R.id.articleActivityRecyclerView);
        recyclerView.setLayoutManager(linearLayoutManager);
        recyclerView.setAdapter(articleAdapter);
        Toolbar toolbar = findViewById(R.id.toolbarArticle);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final boolean shareContent = sharedPreferences.getBoolean("shareOnSocialNetworks", true);

        final CollapsingToolbarLayout collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        AppBarLayout appBarLayout = findViewById(R.id.articleAppBarLayout);
        appBarLayout.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {

            @Override
            public void onOffsetChanged(@NonNull AppBarLayout appBarLayout, int verticalOffset) {
                if (shareItem == null) {
                    return;
                }
                // XXX: convert to independent unit
                //Log.d(TAG, "verticalOffset = " + verticalOffset);
                View share = findViewById(R.id.action_share);

                if (shareContent && Math.abs(verticalOffset) - appBarLayout.getTotalScrollRange() == 0) {
                    shareItem.setVisible(true);
                    if (share != null) {
                        share.startAnimation(animationFadeIn);
                    }
                } else {
                    shareItem.setVisible(false);
                }
            }
        });

        if (REQUEST_QUEUE == null) {
            REQUEST_QUEUE = Volley.newRequestQueue(this);
        }

        autoLoader = findViewById(R.id.autoLoader);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (recyclerView.getLayoutManager() != null && linearLayoutManager.findLastCompletelyVisibleItemPosition() == recyclerView.getLayoutManager().getItemCount() - 1) {
                    if (!"".equals(commentsURI) && !isRestricted) {
                        autoLoader.setVisibility(View.VISIBLE);
                        REQUEST_QUEUE.add(new StringRequest(Request.Method.GET, commentsURI, commentsReceived, errorResponse));
                    }
                } else {
                    autoLoader.setVisibility(View.INVISIBLE);
                }
                super.onScrolled(recyclerView, dx, dy);
            }
        });

        // If user is opening a link from another App, like a mail client for instance
        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            shareLink = intent.getDataString();
        } else if (intent.getExtras() != null) {
            collapsingToolbar.setTitle(intent.getExtras().getString(Constants.EXTRA_NEWS_CATEGORY));
            Picasso.with(this)
                    .load(intent.getExtras().getString(Constants.EXTRA_RSS_IMAGE))
                    .into((ImageView) findViewById(R.id.imageArticle));
            shareLink = intent.getExtras().getString(Constants.EXTRA_RSS_LINK);
        }

        // Start async job
        int lastFirstVisiblePosition = getIntent().getIntExtra(STATE_RECYCLER_VIEW_POS, -1);
        // If we have stored the position, it means we also have stored state and items from the recycler view
        if (lastFirstVisiblePosition >= 0) {
            Parcelable parcelable = getIntent().getParcelableExtra(STATE_RECYCLER_VIEW);
            if (recyclerView.getLayoutManager() != null) {
                recyclerView.getLayoutManager().onRestoreInstanceState(parcelable);
            }
            List<Model> items = getIntent().getParcelableArrayListExtra(STATE_ADAPTER_ITEM);
            if (items != null) {
                articleAdapter.addItems(items);
            }
            recyclerView.getLayoutManager().scrollToPosition(lastFirstVisiblePosition);
        } else {
            REQUEST_QUEUE.add(new StringRequest(Request.Method.GET, shareLink, articleReceived, errorResponse));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Do not display the loader once again after resuming this activity
        if (articleAdapter.getItemCount() > 0) {
            findViewById(R.id.articleLoader).setVisibility(View.GONE);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Save position, state, and items before rotating the device
        if (recyclerView.getLayoutManager() != null) {
            int lastFirstVisiblePosition = ((LinearLayoutManager) recyclerView.getLayoutManager()).findFirstCompletelyVisibleItemPosition();
            Parcelable parcelable = recyclerView.getLayoutManager().onSaveInstanceState();
            getIntent().putExtra(STATE_RECYCLER_VIEW, parcelable);
            getIntent().putExtra(STATE_RECYCLER_VIEW_POS, lastFirstVisiblePosition);
            getIntent().putParcelableArrayListExtra(STATE_ADAPTER_ITEM, new ArrayList<Parcelable>((articleAdapter.getItems())));
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (android.R.id.home == item.getItemId()) {
            if (Intent.ACTION_VIEW.equals(getIntent().getAction())) {
                startActivity(new Intent(getApplicationContext(), MainActivity.class));
            } else {
                onBackPressed();
            }
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean shareContent = sharedPreferences.getBoolean("shareOnSocialNetworks", true);
        getMenuInflater().inflate(R.menu.articleactivity_right_menu, menu);
        shareItem = menu.findItem(R.id.action_share);
        if (shareContent) {
            /// FIXME
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    animationFadeIn.setDuration(1000);
                    shareItem.setVisible(true);
                }
            }, 1);
            shareItem.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem menuItem) {
                    Intent shareIntent = new Intent();
                    shareIntent.setAction(Intent.ACTION_SEND);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, shareSubject);
                    shareIntent.putExtra(Intent.EXTRA_TEXT, shareText + " " + shareLink);
                    shareIntent.setType("text/plain");
                    startActivity(Intent.createChooser(shareIntent, getResources().getText(R.string.share_article)));
                    return false;
                }
            });
        } else {
            shareItem.setVisible(false);
        }
        toggleFavItem = menu.findItem(R.id.action_toggle_fav);
        toggleFavItem.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                final LeMondeDB leMondeDB = new LeMondeDB(ArticleActivity.this);
                boolean hasArticle = leMondeDB.hasArticle(shareLink);
                if (hasArticle && leMondeDB.deleteArticle(shareLink)) {
                    hasArticle = false;
                    Snackbar.make(findViewById(R.id.coordinatorArticle), getString(R.string.favorites_article_removed), Snackbar.LENGTH_SHORT).show();
                } else {
                    RssItem favorite = new RssItem(RssItem.ARTICLE_TYPE);
                    if (getIntent().getExtras() != null) {
                        favorite.setTitle(getIntent().getExtras().getString(Constants.EXTRA_RSS_TITLE));
                        favorite.setPubDate(getIntent().getExtras().getLong(Constants.EXTRA_RSS_DATE));
                        favorite.setMediaContent(getIntent().getExtras().getString(Constants.EXTRA_RSS_IMAGE));
                    }
                    favorite.setLink(shareLink);
                    favorite.setCategory(getTitle().toString());
                    if (leMondeDB.saveArticle(favorite)) {
                        hasArticle = true;
                        Snackbar.make(findViewById(R.id.coordinatorArticle), getString(R.string.favorites_article_added), Snackbar.LENGTH_SHORT).show();
                    }
                }
                toggleFavIcon(hasArticle);
                return false;
            }
        });
        return true;
    }

    public void openTweet(@NonNull View view) {
        Button button = view.findViewById(R.id.tweet_button);
        String link = button.getContentDescription().toString();
        Uri uri;
        if (link.isEmpty()) {
            uri = Uri.parse("https://www.twitter.com");
        } else {
            uri = Uri.parse(link);
        }
        startActivity(new Intent(Intent.ACTION_VIEW, uri));
    }

    public void openSource(@NonNull View view) {
        Button button = view.findViewById(R.id.graphSource);
        String link = button.getContentDescription().toString();
        Uri uri;
        if (!link.isEmpty()) {
            uri = Uri.parse(link);
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        }
    }

    public void register(@SuppressWarnings("unused") View view) {
        Uri uri = Uri.parse("https://abo.lemonde.fr");
        startActivity(new Intent(Intent.ACTION_VIEW, uri));
    }

    private void toggleFavIcon(boolean hasArticle) {
        if (isRestricted && toggleFavItem != null) {
            if (hasArticle) {
                toggleFavItem.setIcon(getResources().getDrawable(R.drawable.star_full_black));
            } else {
                toggleFavItem.setIcon(getResources().getDrawable(R.drawable.star_border_black));
            }
        } else if (toggleFavItem != null) {
            if (hasArticle) {
                if (ThemeUtils.isDarkTheme(ArticleActivity.this)) {
                    toggleFavItem.setIcon(getResources().getDrawable(R.drawable.star_full_light));
                } else {
                    toggleFavItem.setIcon(getResources().getDrawable(R.drawable.star_full_black));
                }
            } else {
                if (ThemeUtils.isDarkTheme(ArticleActivity.this)) {
                    toggleFavItem.setIcon(getResources().getDrawable(R.drawable.star_border_light));
                } else {
                    toggleFavItem.setIcon(getResources().getDrawable(R.drawable.star_border_black));
                }
            }
        }
    }

    /**
     * This helper method is used to customize the header (in the AppBar) to display a tag or a bubble when the current
     * article is restricted to paid members or is a video.
     *
     * @param stringId        string to display in the AppBar
     * @param backgroundColor color of the background
     * @param textColor       color of the text to display
     */
    private void setTagInHeader(int stringId, int backgroundColor, int textColor) {
        TextView tagArticle = findViewById(R.id.tagArticle);
        tagArticle.setText(getString(stringId));
        tagArticle.setBackgroundColor(ContextCompat.getColor(getBaseContext(), backgroundColor));
        tagArticle.setTextColor(textColor);
        tagArticle.setVisibility(View.VISIBLE);
    }
}