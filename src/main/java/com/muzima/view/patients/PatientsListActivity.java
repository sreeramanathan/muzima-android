/*
 * Copyright (c) 2014. The Trustees of Indiana University.
 *
 * This version of the code is licensed under the MPL 2.0 Open Source license with additional
 * healthcare disclaimer. If the user is an entity intending to commercialize any application
 * that uses this code in a for-profit venture, please contact the copyright holder.
 */

package com.muzima.view.patients;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.*;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.widget.SearchView;
import com.muzima.MuzimaApplication;
import com.muzima.R;
import com.muzima.adapters.ListAdapter;
import com.muzima.adapters.patients.PatientsLocalSearchAdapter;
import com.muzima.api.model.Cohort;
import com.muzima.api.model.Patient;
import com.muzima.api.model.User;
import com.muzima.biometric.model.PatientModels;
import com.muzima.controller.CohortController;
import com.muzima.controller.PatientController;
import com.muzima.search.api.util.StringUtil;
import com.muzima.utils.Fonts;
import com.muzima.utils.NetworkUtils;
import com.muzima.utils.barcode.IntentIntegrator;
import com.muzima.utils.barcode.IntentResult;
import com.muzima.utils.fingerprint.PatientModelBuilder;
import com.muzima.view.BroadcastListenerActivity;
import com.muzima.view.forms.RegistrationFormsActivity;
import com.muzima.view.notifications.SyncNotificationsIntent;

import java.util.ArrayList;
import java.util.List;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static com.muzima.utils.Constants.*;
import static com.muzima.utils.Constants.DataSyncServiceConstants.SyncStatusConstants;

public class PatientsListActivity extends BroadcastListenerActivity implements AdapterView.OnItemClickListener, ListAdapter.BackgroundListQueryTaskListener {
    public static final String COHORT_ID = "cohortId";
    public static final String COHORT_NAME = "cohortName";
    public static final String QUICK_SEARCH = "quickSearch";
    public static final String NOTIFICATIONS = "Notifications";
    public static boolean isNotificationsList = false;
    private MenuItem menubarSyncButton;
    private boolean notificationsSyncInProgress;

    private ListView listView;
    private boolean quickSearch = false;
    private String cohortId = null;

    private PatientsLocalSearchAdapter patientAdapter;
    private FrameLayout progressBarContainer;
    private View noDataView;
    private String searchString;
    private Button searchServerBtn;
    private SearchView searchView;
    private boolean intentBarcodeResults = false;
    private boolean intentFingerPrintResults = false;
    private boolean intentFingerPrintScanResults = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient_list);
        Bundle intentExtras = getIntent().getExtras();
        if (intentExtras != null) {
            quickSearch = intentExtras.getBoolean(QUICK_SEARCH);
            cohortId = intentExtras.getString(COHORT_ID);
            String title = intentExtras.getString(COHORT_NAME);
            if (title != null) {
                isNotificationsList = StringUtil.equals(title, NOTIFICATIONS);
                setTitle(title);
            }
        } else
            isNotificationsList = false;

        progressBarContainer = (FrameLayout) findViewById(R.id.progressbarContainer);
        setupNoDataView();
        setupListView(cohortId);

        searchServerBtn = (Button) findViewById(R.id.search_server_btn);
        searchServerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(PatientsListActivity.this, PatientRemoteSearchListActivity.class);
                Bundle bundle = new Bundle();
                bundle.putString(SEARCH_STRING_BUNDLE_KEY, searchString);
                intent.putExtras(bundle);
                startActivity(intent);
            }
        });
        if (isNotificationsList)
            searchServerBtn.setVisibility(View.GONE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getSupportMenuInflater().inflate(R.menu.client_list, menu);
        searchView = (SearchView) menu.findItem(R.id.search)
                .getActionView();
        searchView.setQueryHint("Search clients");
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String s) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String s) {
                searchString = s;
                activateRemoteAfterThreeCharacterEntered(s);
                patientAdapter.search(s.trim());
                return true;
            }
        });

        if (quickSearch) {
            searchView.setIconified(false);
            searchView.requestFocus();
        } else
            searchView.setIconified(true);

        menubarSyncButton = menu.findItem(R.id.menu_load);

        if (isNotificationsList) {
            menubarSyncButton.setVisible(true);
            searchView.setVisibility(View.GONE);
        } else {
            menubarSyncButton.setVisible(false);
            searchView.setVisibility(View.VISIBLE);
        }

        super.onCreateOptionsMenu(menu);
        return true;
    }

    private void activateRemoteAfterThreeCharacterEntered(String searchString) {
        if (searchString.trim().length() < 3) {
            searchServerBtn.setVisibility(View.GONE);
        } else {
            searchServerBtn.setVisibility(View.VISIBLE);
        }
    }

    // Confirmation dialog for confirming if the patient have an existing ID
    private void callConfirmationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(PatientsListActivity.this);
        builder
                .setCancelable(true)
                .setIcon(getResources().getDrawable(R.drawable.ic_warning))
                .setTitle(getResources().getString(R.string.confirm))
                .setMessage(getResources().getString(R.string.patient_registration_id_card_question))
                .setPositiveButton("Yes", setFocusToSearch())
                .setNegativeButton("No", openRegistrationForm()).create().show();
    }

    private void callRegistrationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(PatientsListActivity.this);
        builder
                .setCancelable(true)
                .setIcon(getResources().getDrawable(R.drawable.ic_warning))
                .setTitle(getResources().getString(R.string.confirm))
                .setMessage(getResources().getString(R.string.patient_fingerprint_not_found_question))
                .setPositiveButton("Yes", openRegistrationForm())
                .setNegativeButton("No", setFocusToSearch()).create().show();
    }

    private Dialog.OnClickListener setFocusToSearch() {
        return new Dialog.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                searchView.setIconified(false);
                searchView.requestFocus();
            }
        };
    }

    private Dialog.OnClickListener openRegistrationForm() {
        return new Dialog.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                startActivity(new Intent(PatientsListActivity.this, RegistrationFormsActivity.class));
            }
        };
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.fingerprint:
                PatientModels modelsForIntent = getPatientModelsForIntent();
                invokeFingerPrintIdentification(modelsForIntent);
                return true;
            case R.id.menu_client_add:
                callConfirmationDialog();
                return true;
            case R.id.scan:
                invokeBarcodeScan();
                return true;
            case R.id.menu_load:
                if (notificationsSyncInProgress) {
                    Toast.makeText(this, "Action not allowed while sync is in progress", Toast.LENGTH_SHORT).show();
                    return true;
                }

                if (!NetworkUtils.isConnectedToNetwork(this)) {
                    Toast.makeText(this, "No connection found, please connect your device and try again", Toast.LENGTH_SHORT).show();
                    return true;
                }

                syncAllNotificationsInBackgroundService();
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private PatientModels getPatientModelsForIntent() {
        PatientController patientController = ((MuzimaApplication) getApplicationContext()).getPatientController();
        PatientModelBuilder patientModelBuilder = new PatientModelBuilder();

        List<Patient> allPatients = null;
        try {
            allPatients = patientController.getAllPatients();
        } catch (PatientController.PatientLoadException e) {
            allPatients = new ArrayList<Patient>();
        }
        return patientModelBuilder.build(allPatients);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!intentBarcodeResults)
            patientAdapter.reloadData();
    }

    private void setupListView(String cohortId) {
        listView = (ListView) findViewById(R.id.list);
        listView.setEmptyView(findViewById(R.id.no_data_layout));
        patientAdapter = new PatientsLocalSearchAdapter(getApplicationContext(),
                R.layout.layout_list,
                ((MuzimaApplication) getApplicationContext()).getPatientController(),
                cohortId, isNotificationsList);
        patientAdapter.setBackgroundListQueryTaskListener(this);
        listView.setAdapter(patientAdapter);
        listView.setOnItemClickListener(this);
    }

    private void setupNoDataView() {

        noDataView = findViewById(R.id.no_data_layout);

        TextView noDataMsgTextView = (TextView) findViewById(R.id.no_data_msg);
        if (isNotificationsList)
            noDataMsgTextView.setText(getResources().getText(R.string.no_notification_available));
        else
            noDataMsgTextView.setText(getResources().getText(R.string.no_clients_matched_locally));

        TextView noDataTipTextView = (TextView) findViewById(R.id.no_data_tip);

        if (isNotificationsList)
            noDataTipTextView.setText(R.string.no_notification_available_tip);
        else
            noDataTipTextView.setText(R.string.no_clients_matched_tip_locally);

        noDataMsgTextView.setTypeface(Fonts.roboto_bold_condensed(this));
        noDataTipTextView.setTypeface(Fonts.roboto_light(this));
    }

    public void showPatientDetails(String patientId) {
        if (PATIENT_NOT_FOUND_RETURN_VALUE.equalsIgnoreCase(patientId)) {
            callRegistrationDialog();
        } else {
            Patient patient = patientAdapter.getPatientById(patientId);
            showPatientDetails(patient);
        }
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long l) {
        Patient patient = patientAdapter.getItem(position);
        showPatientDetails(patient);
    }

    private void showPatientDetails(Patient patient) {
        Intent intent = new Intent(this, PatientSummaryActivity.class);
        intent.putExtra(PatientSummaryActivity.PATIENT, patient);
        startActivity(intent);
    }

    @Override
    public void onQueryTaskStarted() {
        listView.setVisibility(INVISIBLE);
        noDataView.setVisibility(INVISIBLE);
        progressBarContainer.setVisibility(VISIBLE);
    }

    @Override
    public void onQueryTaskFinish() {

        listView.setVisibility(VISIBLE);
        progressBarContainer.setVisibility(INVISIBLE);
    }

    @Override
    protected void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        int syncStatus = intent.getIntExtra(DataSyncServiceConstants.SYNC_STATUS, SyncStatusConstants.UNKNOWN_ERROR);
        int syncType = intent.getIntExtra(DataSyncServiceConstants.SYNC_TYPE, -1);

        if (syncType == DataSyncServiceConstants.SYNC_NOTIFICATIONS) {
            hideProgressbar();
            onNotificationDownloadFinish();
        }
    }


    public void hideProgressbar() {
        menubarSyncButton.setActionView(null);
    }

    public void showProgressBar() {
        menubarSyncButton.setActionView(R.layout.refresh_menuitem);
    }

    private void syncAllNotificationsInBackgroundService() {
        notificationsSyncInProgress = true;
        onNotificationDownloadStart();
        showProgressBar();

        User authenticatedUser = ((MuzimaApplication) getApplicationContext()).getAuthenticatedUser();
        if (authenticatedUser != null) {
            // get downloaded cohorts and sync obs and encounters
            List<String> downloadedCohortsUuid = null;
            List<Cohort> downloadedCohorts;
            CohortController cohortController = ((MuzimaApplication) getApplicationContext()).getCohortController();
            try {
                downloadedCohorts = cohortController.getSyncedCohorts();
                downloadedCohortsUuid = new ArrayList<String>();
                for (Cohort cohort : downloadedCohorts) {
                    downloadedCohortsUuid.add(cohort.getUuid());
                }

            } catch (CohortController.CohortFetchException e) {
                e.printStackTrace();
            }
            new SyncNotificationsIntent(this, authenticatedUser.getPerson().getUuid(), getDownloadedCohortsArray(downloadedCohortsUuid)).start();
        } else
            Toast.makeText(this, "Error downloading notifications", Toast.LENGTH_SHORT).show();
    }

    private String[] getDownloadedCohortsArray(List<String> CohortUuids) {
        return CohortUuids.toArray(new String[CohortUuids.size()]);
    }

    public void onNotificationDownloadFinish() {
        notificationsSyncInProgress = false;
        patientAdapter.reloadData();
        //updateSyncText();
    }

    public void onNotificationDownloadStart() {
        notificationsSyncInProgress = true;
    }


    public void invokeBarcodeScan() {
        IntentIntegrator scanIntegrator = new IntentIntegrator(this);

        scanIntegrator.initiateScan();
    }

    public void invokeFingerPrintScan() {
        IntentIntegrator scanIntegrator = new IntentIntegrator(this);
        scanIntegrator.initiateFingerPrintScan();
    }

    public void invokeFingerPrintIdentification(PatientModels modelsForIntent) {
        if (modelsForIntent.getPatientModels().size() > 0) {
            IntentIntegrator scanIntegrator = new IntentIntegrator(this);
            scanIntegrator.initiateFingerPrintIdentification(modelsForIntent);
        } else {
            showDownloadDialog("Alert", "No patients with fingerprint found");
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent dataIntent) {
        IntentResult fingerPrintScanningResult = IntentIntegrator.parseActivityResultForFingerPrint(requestCode, resultCode, dataIntent);
        if (fingerPrintScanningResult != null) {
            this.intentFingerPrintResults = true;
            Log.d("Result", fingerPrintScanningResult.getContents());
            showDownloadDialog("Result", fingerPrintScanningResult.getContents());
            return;
        }
        IntentResult fingerPrintIdentificationResult = IntentIntegrator.parseActivityResultForFingerPrintIdentification(requestCode, resultCode, dataIntent);
        if (fingerPrintIdentificationResult != null) {
            this.intentFingerPrintScanResults = true;
            String identifiedPatientUUID = fingerPrintIdentificationResult.getContents();
            Log.d("IdentifiedUser", identifiedPatientUUID);
            showPatientDetails(identifiedPatientUUID);
            return;
        }
        IntentResult scanningResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, dataIntent);
        if (scanningResult != null) {
            intentBarcodeResults = true;
            searchView.setQuery(scanningResult.getContents(), false);
            return;
        }
    }

    private void showDownloadDialog(String dialogTitle, String dialogMessage) {
        final AlertDialog.Builder downloadDialog = new AlertDialog.Builder(this);
        downloadDialog.setTitle(dialogTitle);
        downloadDialog.setMessage(dialogMessage);
        downloadDialog.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                dialog.cancel();
            }

        });
        downloadDialog.show();
    }

}