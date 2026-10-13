package me.tagavari.airmessage.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.tagavari.airmessage.R;
import me.tagavari.airmessage.extension.FragmentCommunicationNetworkConfig;
import me.tagavari.airmessage.flavor.WelcomeGoogleSignIn;

/**
 * Login screen: Google sign-in only
 */
public class FragmentOnboardingWelcome extends FragmentCommunication<FragmentCommunicationNetworkConfig> {
	public static final String TAG = FragmentOnboardingWelcome.class.getName();
	
	private WelcomeGoogleSignIn welcomeGoogleSignIn;
	
	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_onboarding_welcome, container, false);
	}
	
	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		welcomeGoogleSignIn = new WelcomeGoogleSignIn(this);
		
		View button = view.findViewById(R.id.button_connect_google);
		button.setOnClickListener(v -> welcomeGoogleSignIn.launch());
		if(!welcomeGoogleSignIn.isSupported()) {
			//Builds without Firebase have no login
			button.setVisibility(View.GONE);
			if(getCommunicationsCallback() != null) getCommunicationsCallback().launchConversations();
		}
	}
	
	/** Shows or hides the loading bar while the sign-in request is in flight */
	public void setLoading(boolean loading) {
		View root = getView();
		if(root == null) return;
		root.findViewById(R.id.progress_signin).setVisibility(loading ? View.VISIBLE : View.GONE);
		root.findViewById(R.id.button_connect_google).setEnabled(!loading);
	}
}
