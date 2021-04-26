package org.thoughtcrime.securesms.maps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Allows selection of an address from a google map.
 * <p>
 * Based on https://github.com/suchoX/PlacePicker
 */
public final class PlacePickerActivity extends AppCompatActivity {

  private static final String TAG = Log.tag(PlacePickerActivity.class);

  // If it cannot load location for any reason, it defaults to the prime meridian.
  private static final LatLng PRIME_MERIDIAN = new LatLng(51.4779, -0.0015);
  private static final String ADDRESS_INTENT = "ADDRESS";
  private static final float  ZOOM           = 17.0f;

  private static final int                   ANIMATION_DURATION     = 250;
  private static final OvershootInterpolator OVERSHOOT_INTERPOLATOR = new OvershootInterpolator();

  private SingleAddressBottomSheet bottomSheet;
  private Address                  currentAddress;
  private LatLng                   initialLocation;
  private LatLng                   currentLocation = new LatLng(0, 0);
  private AddressLookup            addressLookup;
  private GoogleMap                googleMap;

  public static void startActivityForResultAtCurrentLocation(@NonNull Activity activity, int requestCode) {
    activity.startActivityForResult(new Intent(activity, PlacePickerActivity.class), requestCode);
  }

  public static AddressData addressFromData(@NonNull Intent data) {
    return data.getParcelableExtra(ADDRESS_INTENT);
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_place_picker);

    bottomSheet      = findViewById(R.id.bottom_sheet);
    View markerImage = findViewById(R.id.marker_image_view);
    View fab         = findViewById(R.id.place_chosen_button);

    if (BuildConfig.USE_OSM) {
      findViewById(R.id.map_type_overlay).setVisibility(View.GONE);
    } else {
      findViewById(R.id.btnMapTypeNormal).setOnClickListener(v -> handleMapType("normal"));
      findViewById(R.id.btnMapTypeSatellite).setOnClickListener(v -> handleMapType("satellite"));
      findViewById(R.id.btnMapTypeTerrain).setOnClickListener(v -> handleMapType("terrain"));
    }

    fab.setOnClickListener(v -> finishWithAddress());

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    {
      new LocationRetriever(this, this, location -> {
        setInitialLocation(new LatLng(location.getLatitude(), location.getLongitude()));
      }, () -> {
        Log.w(TAG, "Failed to get location.");
        setInitialLocation(PRIME_MERIDIAN);
      });
    } else {
      Log.w(TAG, "No location permissions");
      setInitialLocation(PRIME_MERIDIAN);
    }

    SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
    if (mapFragment == null) throw new AssertionError("No map fragment");

    mapFragment.getMapAsync(googleMap -> {

      setMap(googleMap);

      enableMyLocationButtonIfHaveThePermission(googleMap);

      googleMap.setOnCameraMoveStartedListener(i -> {
        markerImage.animate()
                   .translationY(-75f)
                   .setInterpolator(OVERSHOOT_INTERPOLATOR)
                   .setDuration(ANIMATION_DURATION)
                   .start();

        bottomSheet.hide();
      });

      googleMap.setOnCameraIdleListener(() -> {
        markerImage.animate()
                   .translationY(0f)
                   .setInterpolator(OVERSHOOT_INTERPOLATOR)
                   .setDuration(ANIMATION_DURATION)
                   .start();

        setCurrentLocation(googleMap.getCameraPosition().target);
      });
    });
  }

  private void setInitialLocation(@NonNull LatLng latLng) {
    initialLocation = latLng;

    moveMapToInitialIfPossible();
  }

  private void setMap(GoogleMap googleMap) {
    this.googleMap = googleMap;

    setMapType(TextSecurePreferences.getGoogleMapType(this));
    moveMapToInitialIfPossible();
  }

  private void handleMapType(final String mapType) {
    setMapType(mapType);
    TextSecurePreferences.setGoogleMapType(this, mapType);
  }

  private void setMapType(final String mapType) {
    if (googleMap == null) {
      return;
    }
    switch (mapType) {
      case "hybrid":    googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);    break;
      case "satellite": googleMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE); break;
      case "terrain":   googleMap.setMapType(GoogleMap.MAP_TYPE_TERRAIN);   break;
      case "none":      googleMap.setMapType(GoogleMap.MAP_TYPE_NONE);      break;
      default:          googleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);    break;
    }
  }

  private void moveMapToInitialIfPossible() {
    if (initialLocation != null && googleMap != null) {
      Log.d(TAG, "Moving map to initial location");
      googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialLocation, ZOOM));
      setCurrentLocation(initialLocation);
    }
  }

  private void setCurrentLocation(LatLng location) {
    currentLocation = location;
    bottomSheet.showLoading();
    lookupAddress(location);
  }

  private void finishWithAddress() {
    Intent      returnIntent = new Intent();
    String      address      = currentAddress != null && currentAddress.getAddressLine(0) != null ? currentAddress.getAddressLine(0) : "";
    AddressData addressData  = new AddressData(currentLocation.latitude, currentLocation.longitude, address);

    returnIntent.putExtra(ADDRESS_INTENT, addressData);
    setResult(RESULT_OK, returnIntent);
    finish();
  }

  private void enableMyLocationButtonIfHaveThePermission(GoogleMap googleMap) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    {
      googleMap.setMyLocationEnabled(true);
    }
  }

  private void lookupAddress(@Nullable LatLng target) {
    if (addressLookup != null) {
      addressLookup.cancel(true);
    }
    addressLookup = new AddressLookup();
    addressLookup.execute(target);
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (addressLookup != null) {
      addressLookup.cancel(true);
    }
  }

  @SuppressLint("StaticFieldLeak")
  private class AddressLookup extends AsyncTask<LatLng, Void, Address> {

    private final String TAG = Log.tag(AddressLookup.class);
    private final Geocoder geocoder;

    AddressLookup() {
      geocoder = new Geocoder(getApplicationContext(), Locale.getDefault());
    }

    @Override
    protected Address doInBackground(LatLng... latLngs) {
      if (latLngs.length == 0) return null;
      LatLng latLng = latLngs[0];
      if (latLng == null) return null;
      try {
        List<Address> result = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1);
        return !result.isEmpty() ? result.get(0) : null;
      } catch (IOException e) {
        Log.w(TAG, "Failed to get address from location", e);
        return null;
      }
    }

    @Override
    protected void onPostExecute(@Nullable Address address) {
      currentAddress = address;
      if (address != null) {
        bottomSheet.showResult(address.getLatitude(), address.getLongitude(), addressToShortString(address), addressToString(address));
      } else {
        bottomSheet.hide();
      }
    }
  }

  private static @NonNull String addressToString(@Nullable Address address) {
    return address != null ? address.getAddressLine(0) : "";
  }

  private static @NonNull String addressToShortString(@Nullable Address address) {
    if (address == null) return "";

    String   addressLine = address.getAddressLine(0);
    String[] split       = addressLine.split(",");

    if (split.length >= 3) {
      return split[1].trim() + ", " + split[2].trim();
    } else if (split.length == 2) {
      return split[1].trim();
    } else return split[0].trim();
  }
}
