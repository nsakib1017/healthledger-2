package com.subsel.healthledger.core.controller;

import com.fasterxml.jackson.databind.JsonNode;

import com.subsel.healthledger.common.controller.BaseController;
import com.subsel.healthledger.core.model.UserPOJO;
import com.subsel.healthledger.util.FabricNetworkConstants;
import com.subsel.healthledger.util.FabricUtils;
import okhttp3.Request;
import org.hyperledger.fabric.gateway.*;

import org.hyperledger.fabric.sdk.Enrollment;
import org.hyperledger.fabric.sdk.User;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric.sdk.security.CryptoSuiteFactory;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.*;

@RestController
@RequestMapping(path = "/api/users")
public class UserController extends BaseController {

    @PostMapping(value = "/", consumes = "application/json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> registerUser(@RequestBody UserPOJO userPOJO) throws Exception{
        // Create a CA client for interacting with the CA.
        Map<String, Object> response = new HashMap<>();
        HttpHeaders httpHeaders = new HttpHeaders();

        Properties props = new Properties();
        props.put("pemFile", FabricUtils.getNetworkConfigCertPath(userPOJO.getMspOrg()));
        props.put("allowAllHostNames", "true");
        HFCAClient caClient = HFCAClient.createNewInstance(FabricUtils.getHFAClientURL(userPOJO.getMspOrg()), props);
        CryptoSuite cryptoSuite = CryptoSuiteFactory.getDefault().getCryptoSuite();
        caClient.setCryptoSuite(cryptoSuite);

        // Create a wallet for managing identities
        Wallet wallet = Wallets.newFileSystemWallet(Paths.get(FabricNetworkConstants.wallet));

        // Check to see if we've already enrolled the user.
        if (wallet.get(userPOJO.getUserName()) != null) {
            String message = "An identity for the user \"appUser\" already exists in the wallet";
            HttpHeaders headers = new HttpHeaders();
            response.put("message", message);
            return new ResponseEntity<Map<String, Object>>(response, headers, HttpStatus.BAD_REQUEST);

        }

        X509Identity adminIdentity = (X509Identity)wallet.get(userPOJO.getAdminName());
        if (adminIdentity == null) {
            String message = "\"admin\" needs to be enrolled and added to the wallet first";
            HttpHeaders headers = new HttpHeaders();
            response.put("message", message);
            return new ResponseEntity<Map<String, Object>>(response, headers, HttpStatus.BAD_REQUEST);
        }
        User admin = new User() {

            @Override
            public String getName() {
                return "admin";
            }

            @Override
            public Set<String> getRoles() {
                return null;
            }

            @Override
            public String getAccount() {
                return null;
            }

            @Override
            public String getAffiliation() {
                return FabricUtils.getAffiliatedDept(userPOJO.getMspOrg());
            }

            @Override
            public Enrollment getEnrollment() {
                return new Enrollment() {

                    @Override
                    public PrivateKey getKey() {
                        return adminIdentity.getPrivateKey();
                    }

                    @Override
                    public String getCert() {
                        return Identities.toPemString(adminIdentity.getCertificate());
                    }
                };
            }

            @Override
            public String getMspId() {
                return FabricUtils.OrgMsp.valueOf(userPOJO.getMspOrg()).toString();
            }

        };

        // Register the user, enroll the user, and import the new identity into the wallet.
        RegistrationRequest registrationRequest = new RegistrationRequest(userPOJO.getUserName());
        registrationRequest.setAffiliation(FabricUtils.getAffiliatedDept(userPOJO.getMspOrg()));
        registrationRequest.setEnrollmentID(userPOJO.getUserName());
        String enrollmentSecret = caClient.register(registrationRequest, admin);
        Enrollment enrollment = caClient.enroll(userPOJO.getUserName(), enrollmentSecret);
        Identity user = Identities.newX509Identity(FabricUtils.OrgMsp.valueOf(userPOJO.getMspOrg()).toString(), enrollment);
        wallet.put(userPOJO.getUserName(), user);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("username", userPOJO.getUserName());
        requestBody.put("certificate", enrollment.getCert());
        requestBody.put("password", userPOJO.getPassword());
        requestBody.put("mspId", user.getMspId());

         response = FabricUtils.getFabricResults(
                FabricUtils.ContractName.Register.toString(),
                userPOJO.getUserName(),userPOJO.getMspOrg(),
                requestBody
        );
         
        return new ResponseEntity<>(response, httpHeaders, HttpStatus.CREATED);
    }

    @PostMapping(value = "/login", produces = "application/json", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> loginUser(@RequestBody UserPOJO userPOJO) throws Exception {
        Map<String, Object> response = new HashMap<>();
        HttpHeaders httpHeaders = new HttpHeaders();

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("username", userPOJO.getUserName());
        requestBody.put("password", userPOJO.getPassword());

        response = FabricUtils.getFabricResults(
                FabricUtils.ContractName.Login.toString(),
                userPOJO.getUserName(),
                userPOJO.getMspOrg(),
                requestBody
        );

        return new ResponseEntity<>(response, httpHeaders, HttpStatus.OK);
    }

    @GetMapping(value="/data/{id}")
    public ResponseEntity<Map<String, Object>> getUserData(@PathVariable String id, @RequestBody Map<String, String> allParam) throws Exception {

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("id", id);
        String username = allParam.get("username");
        String mspOrg = allParam.get("mspOrg");

        Map<String, Object>  response = FabricUtils.getFabricResults(
                FabricUtils.ContractName.ReadEhr.toString(),
                username,
                mspOrg,
                requestBody
        );

        JsonNode resultObj = (JsonNode) response.get("results");
        String data = String.valueOf(resultObj.get("Data"));

        if (data.equals(FabricUtils.permissionStatus.yes.toString())) {
                if(Integer.parseInt(String.valueOf(resultObj.get("Maturity"))) < new Date().getTime()) {
                    requestBody.put("data", FabricUtils.permissionStatus.no.toString());
                    response = FabricUtils.getFabricResults(
                            FabricUtils.ContractName.ChangeData.toString(),
                            username,
                            mspOrg,
                            requestBody
                    );
                } else {
                    requestBody.put("id", String.valueOf(resultObj.get("Key")));
                    response = FabricUtils.getFabricResults(
                            FabricUtils.ContractName.ReadEhr.toString(),
                            username,
                            mspOrg,
                            requestBody
                    );
                }
            } else {
                response.put("message", "Permission denied");
            }

        HttpHeaders httpHeaders = new HttpHeaders();
        return new ResponseEntity<>(response, httpHeaders, HttpStatus.OK);
    }
}
