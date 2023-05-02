package com.example.uberclonerider.ui.home



import android.Manifest
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.uberclonerider.Callback.FirebaseDriverInfoListener
import com.example.uberclonerider.Callback.FirebaseFailedListener
import com.example.uberclonerider.Model.DriverGeoModel
import com.example.uberclonerider.Model.DriverInfoModel
import com.example.uberclonerider.Model.GeoQueryModel
import com.example.uberclonerider.R
import com.example.uberclonerider.databinding.FragmentHomeBinding
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoLocation
import com.firebase.geofire.GeoQuery
import com.firebase.geofire.GeoQueryEventListener
import com.google.android.gms.common.internal.service.Common
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.IOException
import java.util.*
import kotlin.collections.ArrayList

class HomeFragment : Fragment(), OnMapReadyCallback, FirebaseDriverInfoListener {

    private var _binding: FragmentHomeBinding? = null
    private lateinit var mMap: GoogleMap
    lateinit var mapFragment:SupportMapFragment

    //Location
    lateinit var locationRequest: LocationRequest
    lateinit var locationCallback: LocationCallback
    lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    //Load Driver
    var distance = 1.0
    val LIMIT_RANGE = 10.0
    var previousLocation: Location? = null
    var currentLocation:Location? = null

    var firstTime = true

    //Listener
    lateinit var iFirebaseDriverInfoListener: FirebaseDriverInfoListener
    lateinit var iFirebaseFailedListener: FirebaseFailedListener

    var cityName = ""

    override fun onDestroy() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel =
            ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        init()

        mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        return root
    }

    private fun init() {

        iFirebaseDriverInfoListener = this
        locationRequest = LocationRequest()
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
        locationRequest.setFastestInterval(3000)
        locationRequest.setSmallestDisplacement(10f)
        locationRequest.interval = 5000

        locationCallback = object: LocationCallback(){
            //Ctrl+o
            override fun onLocationResult(p0: LocationResult) {
                super.onLocationResult(p0)
                val newPos = LatLng(p0.lastLocation!!.latitude,p0.lastLocation!!.longitude)
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newPos,18f))

                //If use has change the Location, calculate and load driver again
                if (firstTime)
                {
                    previousLocation = p0.lastLocation
                    currentLocation = p0.lastLocation

                    firstTime = false
                }
                else
                {
                    previousLocation = currentLocation
                    currentLocation = p0.lastLocation
                }

                if (previousLocation!!.distanceTo(currentLocation!!) / 1000 <= LIMIT_RANGE)
                    loadAvailableDrivers()

            }
        }

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(requireContext())
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper())

        loadAvailableDrivers()
    }

    private fun loadAvailableDrivers() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Snackbar.make(requireView(),getString(R.string.permission_require),Snackbar.LENGTH_LONG).show()
            return
        }
        fusedLocationProviderClient.lastLocation
            .addOnFailureListener { e->
                Snackbar.make(requireView(),e.message!!,Snackbar.LENGTH_LONG).show()
            }
            .addOnSuccessListener { location ->

                //Load all drivers in City
                val geoCoder = Geocoder(requireContext(),Locale.getDefault())
                var addressList : List<Address> = ArrayList()
                try {
                    addressList = geoCoder.getFromLocation(location.latitude,location.longitude, 1) as List<Address>
                    cityName = addressList[0].locality

                    //Query
                    val driver_location_ref = FirebaseDatabase.getInstance()
                        .getReference(com.example.uberclonerider.Common.Common.DRIVERS_LOCATION_REFERENCES)
                        .child(cityName)
                    val gf = GeoFire(driver_location_ref)
                    val geoQuery = gf.queryAtLocation(GeoLocation(location.latitude,location.longitude),distance)
                    geoQuery.removeAllListeners()

                    geoQuery.addGeoQueryEventListener(object:GeoQueryEventListener{
                        override fun onKeyEntered(key: String?, location: GeoLocation?) {
                            com.example.uberclonerider.Common.Common.driversFound.add(DriverGeoModel(key!!,location!!))
                        }

                        override fun onKeyExited(key: String?) {

                        }

                        override fun onKeyMoved(key: String?, location: GeoLocation?) {

                        }

                        override fun onGeoQueryReady() {
                            if (distance <= LIMIT_RANGE)
                            {
                                distance++
                                loadAvailableDrivers()
                            }
                            else
                            {
                                distance = 0.0
                                addDriverMarker()
                            }
                        }

                        override fun onGeoQueryError(error: DatabaseError?) {

                            Snackbar.make(requireView(),error!!.message,Snackbar.LENGTH_LONG).show()
                        }

                    })

                    driver_location_ref.addChildEventListener(object:ChildEventListener{
                        override fun onChildAdded(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) {
                            //Have new driver
                            val geoQueryModel = snapshot.getValue(GeoQueryModel::class.java)
                            val geoLocation = GeoLocation(geoQueryModel!!.l!![0],geoQueryModel.l!![1])
                            val driverGeoModel = DriverGeoModel(snapshot.key,geoLocation)
                            val newDriverLocation = Location("")
                            newDriverLocation.latitude = geoLocation.latitude
                            newDriverLocation.longitude = geoLocation.longitude
                            val newDistance = location.distanceTo(newDriverLocation)/1000 // in km
                            if (newDistance <= LIMIT_RANGE)
                                findDriverByKey(driverGeoModel)
                        }

                        override fun onChildChanged(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) {

                        }

                        override fun onChildRemoved(snapshot: DataSnapshot) {

                        }

                        override fun onChildMoved(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) {

                        }

                        override fun onCancelled(error: DatabaseError) {
                            Snackbar.make(requireView(),error.message,Snackbar.LENGTH_LONG).show()
                        }
                    })

                }catch (e:IOException)
                {
                    Snackbar.make(requireView(),getString(R.string.permission_require),Snackbar.LENGTH_LONG).show()
                }
             }
    }

    private fun addDriverMarker() {

        if (com.example.uberclonerider.Common.Common.driversFound.size > 0)
        {
            io.reactivex.rxjava3.core.Observable.fromIterable(com.example.uberclonerider.Common.Common.driversFound)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {driverGeoModel: DriverGeoModel? ->
                        findDriverByKey(driverGeoModel)
                    },
                    {
                        t: Throwable? -> Snackbar.make(requireView(),t!!.message!!,Snackbar.LENGTH_LONG).show()
                    }
            )
        }
        else
        {
            Snackbar.make(requireView(),getString(R.string.drivers_not_found),Snackbar.LENGTH_LONG).show()
        }
    }

    private fun findDriverByKey(driverGeoModel: DriverGeoModel?) {
        FirebaseDatabase.getInstance()
            .getReference(com.example.uberclonerider.Common.Common.DRIVERS_INFO_REFERENCE)
            .child(driverGeoModel!!.key!!)
            .addListenerForSingleValueEvent(object:ValueEventListener{
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.hasChildren())
                    {
                        driverGeoModel.driverInfoModel = (snapshot.getValue(DriverInfoModel::class.java))
                        iFirebaseDriverInfoListener.onDriverInfoLoadSuccess(driverGeoModel)
                    }
                    else
                        iFirebaseFailedListener.onFirebaseFailed(getString(R.string.key_not_found)+driverGeoModel.key)
                }

                override fun onCancelled(error: DatabaseError) {
                    iFirebaseFailedListener.onFirebaseFailed(error.message)
                }

            })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onMapReady(p0: GoogleMap) {
        mMap = p0

        Dexter.withContext(requireContext())
            .withPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            .withListener(object:PermissionListener{
                override fun onPermissionGranted(p0: PermissionGrantedResponse?) {

                    if (ActivityCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }
                    mMap.isMyLocationEnabled = true
                    mMap.uiSettings.isMyLocationButtonEnabled = true
                    mMap.setOnMyLocationButtonClickListener {
                        fusedLocationProviderClient.lastLocation
                            .addOnFailureListener { e->
                                Snackbar.make(requireView(), e.message!!,
                                    Snackbar.LENGTH_SHORT).show()
                            }
                            .addOnSuccessListener { location ->
                                val userLatLng = LatLng(location.latitude, location.longitude)
                                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 18f))
                            }

                        true
                    }

                    //Layout Button
                    val locationButton = (mapFragment.requireView().findViewById<View>("1".toInt()).parent as View)
                        .findViewById<View>("2".toInt())
                    val params = locationButton.layoutParams as RelativeLayout.LayoutParams
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP, 0)
                    params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM,RelativeLayout.TRUE)
                    params.bottomMargin = 250 // Move to see zoom Control

                }

                override fun onPermissionDenied(p0: PermissionDeniedResponse?) {
                    Snackbar.make(requireView(), p0!!.permissionName+" needed for run app",
                        Snackbar.LENGTH_SHORT).show()

                }

                override fun onPermissionRationaleShouldBeShown(
                    p0: PermissionRequest?,
                    p1: PermissionToken?
                ) {

                }

            })
            .check()


        //Enable zoom
        mMap.uiSettings.isZoomControlsEnabled = true


        try {
            val success = p0.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(),R.raw.uber_maps_style))
            if (!success)
                Snackbar.make(requireView(), "Load map style failed",
                    Snackbar.LENGTH_SHORT).show()
        }catch (e:Exception)
        {
            Snackbar.make(requireView(), ""+e.message,
                Snackbar.LENGTH_SHORT).show()
        }


    }

    override fun onDriverInfoLoadSuccess(driverGeoModel: DriverGeoModel?) {
        //If already have marker with this key, doesn't set it again
        if (!com.example.uberclonerider.Common.Common.markerList.containsKey(driverGeoModel!!.key))
            mMap.addMarker(MarkerOptions()
                .position(LatLng(driverGeoModel!!.geoLocation!!.latitude,driverGeoModel!!.geoLocation!!.longitude))
                .flat(true)
                .title(com.example.uberclonerider.Common.Common.buildName(driverGeoModel.driverInfoModel!!.firstName,driverGeoModel.driverInfoModel!!.lastName))
                .snippet(driverGeoModel.driverInfoModel!!.phoneNumber)
                .icon(BitmapDescriptorFactory.fromResource(R.drawable.car)))?.let {
                com.example.uberclonerider.Common.Common.markerList.put(driverGeoModel!!.key!!,
                    it
                )
            }

        if (!TextUtils.isEmpty(cityName))
        {
            val driverLocation = FirebaseDatabase.getInstance()
                .getReference(com.example.uberclonerider.Common.Common.DRIVERS_LOCATION_REFERENCES)
                .child(cityName)
                .child(driverGeoModel!!.key!!)
            driverLocation.addValueEventListener(object :ValueEventListener{
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.hasChildren()){
                        if (com.example.uberclonerider.Common.Common.markerList.get(driverGeoModel!!.key!!) != null)
                        {
                            val marker = com.example.uberclonerider.Common.Common.markerList.get(driverGeoModel!!.key!!)
                            marker!!.remove()//Remove marker from map
                            com.example.uberclonerider.Common.Common.markerList.remove(driverGeoModel!!.key!!)//Remove marker information
                            driverLocation.removeEventListener(this)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Snackbar.make(requireView(),error.message,Snackbar.LENGTH_SHORT).show()
                }

            })
        }
    }
}