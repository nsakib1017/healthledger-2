package com.subsel.healthledger.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.subsel.healthledger.common.controller.BaseController;
import com.subsel.healthledger.core.model.EhrPOJO;
import com.subsel.healthledger.core.model.TicketPOJO;
import com.subsel.healthledger.util.FabricUtils;
import com.subsel.healthledger.util.TxnIdGeneretaror;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping(path = "/api/ehr")
public class EhrController extends BaseController {

    @PostMapping(value = "/", consumes = "application/json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> createEhr(@RequestBody EhrPOJO ehrPOJO) throws Exception {

        String ehrId = TxnIdGeneretaror.generate();
        String issued = String.valueOf(new Date().getTime());
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("pointer", ehrId);
        requestBody.put("key", ehrId);
        requestBody.put("username", ehrPOJO.getUname());
        requestBody.put("type", "ehr");
        requestBody.put("data", "QmHash##");
        requestBody.put("issued", issued);
        requestBody.put("maturity", "N/A");

        Map<String, Object> response = FabricUtils.getFabricResults(
                FabricUtils.ContractName.CreateEhr.toString(),
                ehrPOJO.getUname(),
                ehrPOJO.getOrgMsp(),
                requestBody
        );

        HttpHeaders headers = new HttpHeaders();
        return new ResponseEntity<Map<String, Object>>(response, headers, HttpStatus.CREATED);
    }

    @GetMapping(value = "/getAllEhrByUser", produces = "application/json")
    public ResponseEntity<Map<String, Object>> getAllEhrByUser(@RequestParam String username, @RequestParam String orgMsp) throws Exception {

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("username", username);

        Map<String, Object> response = FabricUtils.getFabricResults(
                FabricUtils.ContractName.GetAllEhrByUser.toString(),
                username,
                orgMsp,
                requestBody
        );

        HttpHeaders headers = new HttpHeaders();
        return new ResponseEntity<Map<String, Object>>(response, headers, HttpStatus.OK);
    }

    @GetMapping(value = "/{id}", produces = "application/json")
    public ResponseEntity<Map<String, Object>> getEhr(@PathVariable String id, @RequestParam String username, @RequestParam String orgMsp) throws Exception {

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("id", id);

        Map<String, Object> response = FabricUtils.getFabricResults(
                FabricUtils.ContractName.ReadEhr.toString(),
                username,
                orgMsp,
                requestBody
        );

        HttpHeaders headers = new HttpHeaders();
        return new ResponseEntity<Map<String, Object>>(response, headers, HttpStatus.OK);
    }

    @PostMapping(value="/ticket", produces = "application/json", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> getEhrDataWithTicket(@RequestBody TicketPOJO ticket, @RequestParam String username, @RequestParam String mspOrg) throws Exception {

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("id", ticket.getTicketId());

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
